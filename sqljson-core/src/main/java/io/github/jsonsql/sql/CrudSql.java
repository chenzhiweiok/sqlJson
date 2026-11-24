package io.github.jsonsql.sql;

import io.github.jsonsql.CurrentUser;
import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.meta.EntityDescriptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成那些「不该写进 JSON 的 SQL」：逻辑删除、恢复、批量。
 *
 * <p>为什么它们不在 JSON 里：这些语句随实体的元数据变化（有没有 {@code deleted} 列、
 * 有没有审计列），而 JSON 是静态配置。硬塞进去只能靠模板变量拼，反而更难读。
 * 所以它们在这里按需生成，其余语句仍然走 {@code SqlV2}。
 *
 * <p>这个类只产出 {@link SqlStatement}，<b>不执行、不拿连接</b>。
 * 想测一条 SQL 长什么样，调这里就够了，不需要数据库。
 */
public final class CrudSql {

    /** 布尔列用的是 0/1 而不是 true/false，兼容 MySQL、Oracle、SQL Server 的常见建表方式 */
    private static final int DELETED = 1;
    private static final int NOT_DELETED = 0;

    private CrudSql() {
    }

    /**
     * 逻辑删除：{@code update ... set deleted = 1, updated_at = ?, updated_by = ? where id = ? and deleted = 0}。
     *
     * <p>带 {@code deleted = 0} 是为了让重复删除幂等——第二次影响 0 行，而不是又改一次时间戳。
     * 审计列只在实体上存在时才写入。
     *
     * @param desc 元数据，必须声明了逻辑删除列
     * @param id   主键值
     * @return 待执行的语句
     * @throws SqlSpecException 实体没有逻辑删除列
     */
    public static SqlStatement softDelete(EntityDescriptor desc, Object id) {
        requireSoftDelete(desc, "删除");
        StringBuilder sb = new StringBuilder("update ").append(desc.table())
                .append(" set ").append(desc.deletedColumn()).append(" = ").append(DELETED);
        List<Object> args = new ArrayList<>();
        appendAudit(sb, args, desc);
        sb.append(" where ").append(desc.idColumn()).append(" = ?")
                .append(" and ").append(desc.deletedColumn()).append(" = ").append(NOT_DELETED);
        args.add(id);
        return new SqlStatement(sb.toString(), args);
    }

    /**
     * 恢复：把 {@code deleted} 置回 0，同时刷新审计字段。
     *
     * <p>不加 {@code deleted = 1} 条件：对一条未删除的记录调用恢复，效果是「刷新时间戳」，
     * 影响 1 行。这比报错更实用——恢复接口通常做成幂等的。
     *
     * @param desc 元数据，必须声明了逻辑删除列
     * @param id   主键值
     * @return 待执行的语句
     * @throws SqlSpecException 实体没有逻辑删除列
     */
    public static SqlStatement restore(EntityDescriptor desc, Object id) {
        requireSoftDelete(desc, "恢复");
        StringBuilder sb = new StringBuilder("update ").append(desc.table())
                .append(" set ").append(desc.deletedColumn()).append(" = ").append(NOT_DELETED);
        List<Object> args = new ArrayList<>();
        appendAudit(sb, args, desc);
        sb.append(" where ").append(desc.idColumn()).append(" = ?");
        args.add(id);
        return new SqlStatement(sb.toString(), args);
    }

    /**
     * 查已删除的数据，给回收站用。
     *
     * <p>这条语句<b>会经过逻辑删除改写器</b>，但因为已经显式写了 {@code deleted = 1}，
     * 改写器检测到后不会再补 {@code deleted = 0}。这个协同点很脆，改动任一边都要回归。
     *
     * @param desc  元数据，必须声明了逻辑删除列
     * @param limit 条数上限，直接拼进 SQL
     * @return 待执行的语句
     * @throws SqlSpecException 实体没有逻辑删除列
     */
    public static SqlStatement findDeleted(EntityDescriptor desc, int limit) {
        requireSoftDelete(desc, "查询回收站");
        String sql = "select " + String.join(", ", desc.allColumnNames())
                + " from " + desc.table()
                + " where " + desc.deletedColumn() + " = " + DELETED
                + " order by " + desc.idColumn() + " desc limit " + Math.max(1, limit);
        return new SqlStatement(sql, List.of());
    }

    /**
     * 批量插入，按 {@code chunkSize} 切成多条语句。
     *
     * <p>只写「在任一实体里出现过值」的列——这样一批里有的字段全为空时不会硬塞 null，
     * 交给数据库默认值。代价是同一批数据可能生成不同形状的 SQL，
     * 所以切块后每条语句单独编译，不能复用同一条 PreparedStatement。
     *
     * @param desc      元数据
     * @param list      待插入实体
     * @param chunkSize 每条语句多少行；小于 1 时按 500
     * @return 按顺序执行的语句列表
     * @throws SqlSpecException 没有任何可写入的列
     */
    public static List<SqlStatement> insertBatch(EntityDescriptor desc, List<?> list, int chunkSize) {
        List<SqlStatement> out = new ArrayList<>();
        for (InsertChunk c : insertChunks(desc, list, chunkSize)) {
            out.add(new SqlStatement(c.sql(), c.args()));
        }
        return out;
    }

    /**
     * 一块 INSERT 的完整信息：SQL、参数，以及<b>这一块实际用到的列</b>。
     *
     * <p>为什么要暴露列：{@code upsertBatch} 需要把「插入列」和「冲突时更新列」拼进
     * 同一条语句，而列清单是按数据动态算出来的（某一批里全为 null 的列不会出现）。
     * 只给 SQL 文本的话，方言层就得再解析一遍 SQL 才能知道列名。
     *
     * @param sql     {@code insert into t (...) values (...),(...)} 形式的 SQL
     * @param columns 本块涉及的列名，顺序与 {@code sql} 中的列一致
     * @param args    参数，与 {@code sql} 中的 {@code ?} 逐一对应
     */
    public record InsertChunk(String sql, List<String> columns, List<Object> args) {
    }

    /**
     * 批量插入，按 {@code chunkSize} 切块，并保留每块的列清单。
     *
     * <p>只写「在任一实体里出现过值」的列——这样一批里有的字段全为空时不会硬塞 null，
     * 交给数据库默认值。代价是同一批数据可能生成不同形状的 SQL，
     * 所以切块后每条语句单独编译，不能复用同一条 PreparedStatement。
     *
     * @param desc      元数据
     * @param list      待插入实体
     * @param chunkSize 每条语句多少行；小于 1 时按 500
     * @return 按顺序排列的分块
     * @throws SqlSpecException 没有任何可写入的列
     */
    public static List<InsertChunk> insertChunks(EntityDescriptor desc, List<?> list, int chunkSize) {
        List<String> cols = columnsWithAnyValue(desc.insertColumns(), list);
        if (cols.isEmpty()) {
            throw new SqlSpecException("insertBatch 没有可写入的列");
        }
        int size = chunkSize < 1 ? 500 : chunkSize;
        List<InsertChunk> out = new ArrayList<>();
        for (int from = 0; from < list.size(); from += size) {
            List<?> chunk = list.subList(from, Math.min(from + size, list.size()));
            StringBuilder sb = new StringBuilder("insert into ").append(desc.table())
                    .append(" (").append(String.join(", ", cols)).append(") values ");
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(');
                for (int j = 0; j < cols.size(); j++) {
                    if (j > 0) {
                        sb.append(", ");
                    }
                    sb.append('?');
                    args.add(SqlV2.itemValue(chunk.get(i), cols.get(j)));
                }
                sb.append(')');
            }
            out.add(new InsertChunk(sb.toString(), cols, args));
        }
        return out;
    }

    /**
     * 批量更新：一条 {@code UPDATE ... SET col = CASE id WHEN ? THEN ? ... END WHERE id IN (...)}。
     *
     * <p>只更新「在任一实体里出现过值」的列；有逻辑删除列时自动加 {@code deleted = 0}，
     * 避免把已删数据改活。
     *
     * @param desc 元数据
     * @param list 待更新实体，调用方需保证每个实体都有主键
     * @return 语句；没有可更新的列时返回 null，调用方按「0 行」处理
     */
    public static SqlStatement updateBatch(EntityDescriptor desc, List<?> list) {
        List<String> cols = columnsWithAnyValue(desc.updateColumns(), list);
        if (cols.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("update ").append(desc.table()).append(" set ");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cols.get(i)).append(" = case ").append(desc.idColumn());
            for (Object e : list) {
                sb.append(" when ? then ?");
                args.add(idOf(desc, e));
                args.add(SqlV2.itemValue(e, cols.get(i)));
            }
            sb.append(" else ").append(cols.get(i)).append(" end");
        }
        sb.append(" where ").append(desc.idColumn()).append(" in (");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
            args.add(idOf(desc, list.get(i)));
        }
        sb.append(')');
        if (desc.softDelete()) {
            sb.append(" and ").append(desc.deletedColumn()).append(" = ").append(NOT_DELETED);
        }
        return new SqlStatement(sb.toString(), args);
    }

    /**
     * 批量逻辑删除，一条 UPDATE 处理一块 id。
     *
     * <p>与 {@link #softDelete} 同构：带 {@code deleted = 0} 让重复删除幂等，
     * 审计列只在实体声明了时才写。分块之间不原子——需要原子请在外层包事务。
     *
     * @param desc 元数据，必须声明逻辑删除列
     * @param ids  已去重排序分块的主键集合
     * @return 按顺序执行的语句，每块一条
     * @throws SqlSpecException 实体没有逻辑删除列
     */
    public static List<SqlStatement> softDeleteBatch(EntityDescriptor desc, IdSet ids) {
        requireSoftDelete(desc, "批量删除");
        List<SqlStatement> out = new ArrayList<>(ids.chunks().size());
        for (List<Object> chunk : ids.chunks()) {
            StringBuilder sb = new StringBuilder("update ").append(desc.table())
                    .append(" set ").append(desc.deletedColumn()).append(" = ").append(DELETED);
            List<Object> args = new ArrayList<>();
            appendAudit(sb, args, desc);
            sb.append(" where ").append(desc.idColumn()).append(" in (");
            appendIdIn(sb, args, chunk);
            sb.append(") and ").append(desc.deletedColumn()).append(" = ").append(NOT_DELETED);
            out.add(new SqlStatement(sb.toString(), args));
        }
        return out;
    }

    /**
     * 批量恢复：把 {@code deleted} 置回 0 并刷新审计字段。
     *
     * <p>与 {@link #restore} 一样不加 {@code deleted = 1} 条件：对未删除的记录调用
     * 只是刷新时间戳，影响行数照常返回，接口保持幂等。
     *
     * @param desc 元数据，必须声明逻辑删除列
     * @param ids  主键集合
     * @return 每块一条语句
     * @throws SqlSpecException 实体没有逻辑删除列
     */
    public static List<SqlStatement> restoreBatch(EntityDescriptor desc, IdSet ids) {
        requireSoftDelete(desc, "批量恢复");
        List<SqlStatement> out = new ArrayList<>(ids.chunks().size());
        for (List<Object> chunk : ids.chunks()) {
            StringBuilder sb = new StringBuilder("update ").append(desc.table())
                    .append(" set ").append(desc.deletedColumn()).append(" = ").append(NOT_DELETED);
            List<Object> args = new ArrayList<>();
            appendAudit(sb, args, desc);
            sb.append(" where ").append(desc.idColumn()).append(" in (");
            appendIdIn(sb, args, chunk);
            sb.append(')');
            out.add(new SqlStatement(sb.toString(), args));
        }
        return out;
    }

    /**
     * 批量物理删除：没有逻辑删除列时 {@link #softDeleteBatch} 的替代品。
     *
     * <p>这是不可恢复的，因此只在实体没有 {@code deleted} 列时使用——
     * 有软删列的实体调到这里属于调用方判断错误，不应该静默退化成物理删除。
     *
     * @param desc 元数据
     * @param ids  主键集合
     * @return 每块一条语句
     */
    public static List<SqlStatement> physicalDelete(EntityDescriptor desc, IdSet ids) {
        List<SqlStatement> out = new ArrayList<>(ids.chunks().size());
        for (List<Object> chunk : ids.chunks()) {
            StringBuilder sb = new StringBuilder("delete from ").append(desc.table())
                    .append(" where ").append(desc.idColumn()).append(" in (");
            List<Object> args = new ArrayList<>();
            appendIdIn(sb, args, chunk);
            sb.append(')');
            out.add(new SqlStatement(sb.toString(), args));
        }
        return out;
    }

    /**
     * SET 子句：一组「列 = ?」赋值，带审计列。
     *
     * @param sql  {@code "col = ?, updated_at = ?"} 形式，不含 {@code set} 关键字
     * @param args 与 {@code sql} 对应的参数
     */
    public record Assign(String sql, List<Object> args) {
    }

    /**
     * 构造「同一批行改成相同值」的 SET 子句。
     *
     * <p><b>列名必须过白名单</b>：这是框架里唯一把调用方字符串拼进 SQL 的地方，
     * 所以先用 {@link EntityDescriptor#columnOf(String)} 解析成真实列名，
     * 再检查它在不在 {@link EntityDescriptor#updateColumnSet()} 里——
     * 主键与逻辑删除列不允许经由此路径修改（改 deleted 会让数据「复活」，
     * 改 id 会让审计与关联全部错位）。
     *
     * @param desc   元数据
     * @param values 字段名/列名 → 新值；空 Map 视为调用错误
     * @return SET 子句与参数
     * @throws SqlSpecException 值为空、字段不存在、或列不可更新
     */
    public static Assign assignments(EntityDescriptor desc, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            throw new SqlSpecException("批量更新至少要指定一个字段");
        }
        Set<String> updatable = desc.updateColumnSet();
        StringBuilder sb = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String column = desc.columnOf(e.getKey());
            if (!updatable.contains(column)) {
                throw new SqlSpecException(desc.type().getSimpleName() + " 的 " + e.getKey()
                        + "(" + column + ") 不可更新：主键与逻辑删除列必须走专用方法");
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(column).append(" = ?");
            args.add(e.getValue());
        }
        appendAudit(sb, args, desc);
        return new Assign(sb.toString(), args);
    }

    /**
     * 批量同值更新：{@code update t set col = ? where id in (...)}。
     *
     * <p>与 {@link #updateBatch} 的分工：那个是「每行值不同」（CASE WHEN，SQL 长度随行数增长），
     * 这个是「所有行同一个值」（一条语句，长度与行数无关）。
     * 一千行批量封号用这个，不要用 CASE WHEN 版本。
     *
     * @param desc   元数据
     * @param assign SET 子句，来自 {@link #assignments}
     * @param ids    主键集合
     * @return 每块一条语句；有逻辑删除列时自动过滤已删数据
     */
    public static List<SqlStatement> updateFields(EntityDescriptor desc, Assign assign, IdSet ids) {
        List<SqlStatement> out = new ArrayList<>(ids.chunks().size());
        for (List<Object> chunk : ids.chunks()) {
            StringBuilder sb = new StringBuilder("update ").append(desc.table())
                    .append(" set ").append(assign.sql())
                    .append(" where ").append(desc.idColumn()).append(" in (");
            List<Object> args = new ArrayList<>(assign.args());
            appendIdIn(sb, args, chunk);
            sb.append(')');
            if (desc.softDelete()) {
                sb.append(" and ").append(desc.deletedColumn()).append(" = ").append(NOT_DELETED);
            }
            out.add(new SqlStatement(sb.toString(), args));
        }
        return out;
    }

    /**
     * 游标分页：{@code where id > ? order by id limit n}。
     *
     * <p>与 {@code limit/offset} 的区别是成本模型：offset 要读过前面所有行才丢弃，
     * 第 5000 页等于白扫十万行；游标每页固定只读一页，靠主键索引定位。
     *
     * <p><b>这里写的是 MySQL 形式的 {@code limit}</b>：调用方（仓库层）必须把它交给
     * {@link io.github.jsonsql.Dialect#rewritePaging(String)} 翻译，Oracle / 达梦
     * 才能变成 {@code fetch first n rows only}。不带方言这一步会在国产库上直接语法错。
     *
     * @param desc   元数据
     * @param lastId 上一页最后一个主键；首页传 0 或极小值
     * @param limit  每页条数，小于 1 按 1
     * @return 待执行的语句
     */
    public static SqlStatement findAfter(EntityDescriptor desc, Object lastId, int limit) {
        StringBuilder sb = new StringBuilder("select ")
                .append(String.join(", ", desc.allColumnNames()))
                .append(" from ").append(desc.table())
                .append(" where ").append(desc.idColumn()).append(" > ?");
        List<Object> args = new ArrayList<>();
        args.add(lastId);
        if (desc.softDelete()) {
            sb.append(" and ").append(desc.deletedColumn()).append(" = ").append(NOT_DELETED);
        }
        sb.append(" order by ").append(desc.idColumn()).append(" asc limit ").append(Math.max(1, limit));
        return new SqlStatement(sb.toString(), args);
    }

    /** 拼 {@code ?, ?, ?} 并追加参数 */
    private static void appendIdIn(StringBuilder sb, List<Object> args, List<Object> ids) {
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i > 0 ? ", ?" : "?");
            args.add(ids.get(i));
        }
    }

    /** 追加审计列赋值；没有声明审计列时什么都不做 */
    private static void appendAudit(StringBuilder sb, List<Object> args, EntityDescriptor desc) {
        if (desc.updatedAtColumn() != null) {
            sb.append(", ").append(desc.updatedAtColumn()).append(" = ?");
            args.add(LocalDateTime.now());
        }
        if (desc.updatedByColumn() != null) {
            sb.append(", ").append(desc.updatedByColumn()).append(" = ?");
            args.add(CurrentUser.get());
        }
    }

    private static void requireSoftDelete(EntityDescriptor desc, String action) {
        if (!desc.softDelete()) {
            throw new SqlSpecException(desc.type().getSimpleName() + " 没有逻辑删除列，无法" + action);
        }
    }

    /** 选出「至少一个实体有值」的列，保持候选列顺序 */
    private static List<String> columnsWithAnyValue(List<String> candidates, List<?> list) {
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            for (Object e : list) {
                if (SqlV2.itemValue(e, c) != null) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** 读主键；读不到按 0 处理，由调用方决定要不要跳过 */
    private static long idOf(EntityDescriptor desc, Object entity) {
        try {
            Object id = desc.idField().get(entity);
            return id instanceof Number n ? n.longValue() : 0L;
        } catch (IllegalAccessException e) {
            return 0L;
        }
    }
}
