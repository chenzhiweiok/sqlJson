package io.github.jsonsql.jdbc;

import io.github.jsonsql.CurrentUser;
import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.meta.EntityDescriptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
        List<String> cols = columnsWithAnyValue(desc.insertColumns(), list);
        if (cols.isEmpty()) {
            throw new SqlSpecException("insertBatch 没有可写入的列");
        }
        int size = chunkSize < 1 ? 500 : chunkSize;
        List<SqlStatement> out = new ArrayList<>();
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
            out.add(new SqlStatement(sb.toString(), args));
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
