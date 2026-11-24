package io.github.jsonsql.criteria;

import io.github.jsonsql.Like;
import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.meta.EntityDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 条件构建器：把「一组 where 条件」表达成一个值对象，供 countBy / existsBy / updateBy 共用。
 *
 * <p><b>为什么需要它</b>：这几个方法的共同点是「条件由调用方在运行期决定」，
 * 而 {@code SqlV2} 的语句是启动期编译好的静态 JSON，表达不了这种动态性。
 * 但直接拼字符串又会有两个问题：列名来自调用方（注入面）、操作符语义丢失
 * （{@code Map.of("createdBefore", t)} 里的 key 既不是列名也不是操作符）。
 * 所以条件先收进这里，渲染时统一做两件事：<b>字段名白名单解析</b>与<b>值参数化</b>。
 *
 * <p><b>字段名用 Java 字段名，不是列名</b>：{@code eq("createdAt", t)} 而不是
 * {@code eq("created_at", t)}。理由是这个 API 的使用者是业务代码，业务代码看到的是实体字段；
 * 字段到列名的映射由 {@link EntityDescriptor#columns()} 提供，写错字段名会立刻抛异常
 * 并列出可用字段，而不是生成一条查不到数据的 SQL。
 *
 * <p><b>条件之间只有 AND</b>：这是刻意的。OR 会让「软删过滤自动追加」变得危险
 * （{@code a or b} 加上 {@code and deleted = 0} 的优先级问题需要加括号，括号又要管嵌套），
 * 而真实业务里绝大多数「批量改/统计」都是 AND 组合。需要 OR 时写一条 JSON 语句，
 * 那里可以精确表达括号。
 *
 * <p><b>它不认识表名</b>：只产出 {@code where} 片段与参数，表名由调用方（仓库层）拼。
 * 这样同一个 Criteria 可以用于 count / exists / update，也可以被将来别的 SQL 复用。
 *
 * <p><b>线程安全</b>：可变对象，构建完即用，不要跨线程共享——它不是缓存，每次请求新建一个。
 */
public final class Criteria {

    /**
     * 渲染结果：{@code " where a = ? and b = ?"} 形式的片段，加上与之逐一对应的参数。
     *
     * <p>{@code where} 以空格开头、以 {@code where} 关键字开头，直接拼在表名后面即可：
     * {@code "select count(*) from t" + rendered.where()}。
     *
     * <p>没有条件、实体也没有逻辑删除列时，{@code where} 是空串（不是 {@code " where "}），
     * 调用方无需特判。
     *
     * @param where SQL 片段，可能为空串
     * @param args  参数值，顺序与片段里的 {@code ?} 一致
     */
    public record Rendered(String where, List<Object> args) {
    }

    /** 单个条件：字段名 + 操作符 + 值；值只存原样，渲染时才转换 */
    private record Part(String field, Op op, Object value) {
    }

    /** 支持的操作符。只收业务里真正常用的，BETWEEN / 子查询这类留给手写语句 */
    private enum Op {
        EQ, NE, GT, GE, LT, LE, IN, IS_NULL, IS_NOT_NULL, LIKE
    }

    private final List<Part> parts = new ArrayList<>();

    private Criteria() {
    }

    /**
     * 新建一个空条件（等于「无条件」，渲染结果为空串）。
     *
     * @return 新的构建器
     */
    public static Criteria of() {
        return new Criteria();
    }

    /**
     * 等值；值为 {@code null} 时渲染成 {@code is null}。
     *
     * <p>null 不报错而是转成 {@code is null}，是因为「按某字段筛空值」本身是常见需求，
     * 而让它报错只会逼调用方写一个 if 分支。想显式表达用 {@link #isNull(String)}。
     *
     * @param field 实体字段名（不是列名）
     * @param value 比较值
     * @return this
     */
    public Criteria eq(String field, Object value) {
        return add(field, value == null ? Op.IS_NULL : Op.EQ, value);
    }

    /**
     * 不等；值为 {@code null} 时渲染成 {@code is not null}。
     *
     * @param field 字段名
     * @param value 比较值
     * @return this
     */
    public Criteria ne(String field, Object value) {
        return add(field, value == null ? Op.IS_NOT_NULL : Op.NE, value);
    }

    /**
     * 大于。值为 null 会抛异常——{@code > null} 在任何数据库里都是「永远不成立」，
     * 静默变成一个查不到数据的条件比报错更难排查。
     *
     * @param field 字段名
     * @param value 比较值，不能为 null
     * @return this
     */
    public Criteria gt(String field, Object value) {
        return addComparable(field, Op.GT, value);
    }

    /** 大于等于；value 不能为 null */
    public Criteria ge(String field, Object value) {
        return addComparable(field, Op.GE, value);
    }

    /** 小于；value 不能为 null */
    public Criteria lt(String field, Object value) {
        return addComparable(field, Op.LT, value);
    }

    /** 小于等于；value 不能为 null */
    public Criteria le(String field, Object value) {
        return addComparable(field, Op.LE, value);
    }

    /**
     * IN 列表。
     *
     * <p><b>空集合渲染成 {@code 1 = 0}</b>，也就是「匹配不到任何行」。这是唯一不会让 SQL
     * 语法出错的表达——{@code in ()} 是非法语法，而退化成「不加条件」会让一次
     * 「批量封号」变成全表封号。宁可查不到，也不能多做。
     *
     * @param field  字段名
     * @param values 候选值，不能为 null；空集合表示匹配不到
     * @return this
     */
    public Criteria in(String field, Collection<?> values) {
        if (values == null) {
            throw new SqlSpecException("in(" + field + ") 的值不能为 null；要表达「不加条件」请不要调用 in");
        }
        return add(field, Op.IN, List.copyOf(values));
    }

    /**
     * 为空。
     *
     * @param field 字段名
     * @return this
     */
    public Criteria isNull(String field) {
        return add(field, Op.IS_NULL, null);
    }

    /**
     * 不为空。
     *
     * @param field 字段名
     * @return this
     */
    public Criteria isNotNull(String field) {
        return add(field, Op.IS_NOT_NULL, null);
    }

    /**
     * 模糊匹配，值对象形式：转义与包装交给 {@link Like}。
     *
     * @param field 字段名
     * @param like  匹配方式与值，不能为 null
     * @return this
     */
    public Criteria like(String field, Like like) {
        if (like == null) {
            throw new SqlSpecException("like(" + field + ") 的值不能为 null");
        }
        return add(field, Op.LIKE, like);
    }

    /**
     * 模糊匹配，字符串形式：等价于 {@link Like#contains(String)}（前后都加 {@code %} 并转义）。
     *
     * <p>要前缀匹配（能走索引）请用 {@link #like(String, Like)} 传 {@code Like.prefix(...)}。
     *
     * @param field 字段名
     * @param value 关键字
     * @return this
     */
    public Criteria like(String field, String value) {
        return value == null ? this : like(field, Like.contains(value));
    }

    /**
     * 当前是否一个条件都没有。
     *
     * <p>注意它<b>不包含</b>逻辑删除条件：那个是 {@link #render(EntityDescriptor)} 按实体元数据
     * 追加的，与调用方写的条件无关。
     *
     * @return true 表示没有显式条件
     */
    public boolean isEmpty() {
        return parts.isEmpty();
    }

    /**
     * 渲染成 {@code where} 片段。
     *
     * <p>两件事在这里发生，而且只在这里发生：
     * <ol>
     *   <li><b>字段名 → 列名</b>：不在实体列清单里的字段直接抛异常，因此拼进 SQL 的列名
     *       永远来自元数据，不来自调用方字符串；</li>
     *   <li><b>逻辑删除过滤</b>：实体声明了软删列时自动追加 {@code deleted = 0}。
     *       放在这里而不是各调用方，是因为「忘了加」的后果是统计到已删数据、
     *       把已删数据改活，而且不会报错。</li>
     * </ol>
     *
     * @param desc 实体元数据，决定列名映射与软删列
     * @return 片段与参数；无条件且无软删列时片段为空串
     * @throws SqlSpecException 字段不存在，或比较类操作符收到 null
     */
    public Rendered render(EntityDescriptor desc) {
        if (desc == null) {
            throw new SqlSpecException("Criteria.render 需要实体元数据");
        }
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();

        for (Part p : parts) {
            // 列名只从元数据来：这一步同时完成「字段名 → 列名」与白名单校验
            String column = desc.columnOf(p.field());
            sql.append(sql.length() == 0 ? " where " : " and ");
            appendPart(sql, args, column, p);
        }
        if (desc.softDelete()) {
            sql.append(sql.length() == 0 ? " where " : " and ")
                    .append(desc.deletedColumn()).append(" = 0");
        }
        return new Rendered(sql.toString(), args);
    }

    // ---------------- 内部 ----------------

    private Criteria add(String field, Op op, Object value) {
        if (field == null || field.isBlank()) {
            throw new SqlSpecException("条件字段名不能为空");
        }
        parts.add(new Part(field.trim(), op, value));
        return this;
    }

    private Criteria addComparable(String field, Op op, Object value) {
        if (value == null) {
            throw new SqlSpecException(field + " 使用 " + op + " 时值不能为 null");
        }
        return add(field, op, value);
    }

    /** 按操作符渲染一个条件；标识符已由调用方解析成列名，值一律参数化 */
    private static void appendPart(StringBuilder sql, List<Object> args, String column, Part p) {
        switch (p.op()) {
            case IS_NULL -> sql.append(column).append(" is null");
            case IS_NOT_NULL -> sql.append(column).append(" is not null");
            case IN -> {
                @SuppressWarnings("unchecked")
                Collection<Object> values = (Collection<Object>) p.value();
                if (values.isEmpty()) {
                    // 空 IN 必须表达成「不匹配任何行」：既保证语法合法，也保证语义不会放大
                    sql.append("1 = 0");
                    return;
                }
                sql.append(column).append(" in (");
                int i = 0;
                for (Object v : values) {
                    sql.append(i++ > 0 ? ", ?" : "?");
                    args.add(v);
                }
                sql.append(')');
            }
            case LIKE -> {
                sql.append(column).append(" like ?");
                args.add(((Like) p.value()).toPattern());
            }
            default -> {
                sql.append(column).append(' ').append(symbol(p.op())).append(" ?");
                args.add(p.value());
            }
        }
    }

    private static String symbol(Op op) {
        return switch (op) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GE -> ">=";
            case LT -> "<";
            case LE -> "<=";
            default -> throw new SqlSpecException("不支持的操作符 " + op);
        };
    }

    @Override
    public String toString() {
        return parts.isEmpty() ? "Criteria(empty)" : "Criteria" + parts;
    }
}
