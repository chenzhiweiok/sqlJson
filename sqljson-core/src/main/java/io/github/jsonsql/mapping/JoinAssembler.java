package io.github.jsonsql.mapping;

import io.github.jsonsql.SqlSpecException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扁平 join 结果集 → 「一个主对象带若干子对象」的组装器。
 *
 * <p>刻意不做对象图：不生成代理、不做懒加载、不做级联保存。
 * 输入是一张已经被数据库 join 平的表格，输出是内存里的两层结构，
 * 层级再深就该在 SQL 里拆成两条语句，而不是继续堆 join。
 *
 * <pre>{@code
 * List<Map<String,Object>> rows = repo.queryMaps("user.withRelations", args);
 * List<UserView> views = JoinAssembler.of(rows, "id", UserView.class)
 *         .many("relations", RelationView.class, "rel_id", "rel_")
 *         .assemble();
 * }</pre>
 *
 * <p>使用前提：子表列必须起别名（如 {@code rel_id, rel_type}），
 * 否则与主表同名列冲突。{@code prefix} 会被剥掉再映射到字段。
 */
public final class JoinAssembler<T> {

    private record Child(String field, Class<?> type, String idColumn, String prefix, boolean many) {
    }

    /** 组装过程的统计，便于观察 join 放大倍数 */
    public record Stats(int rows, int roots, int children, double fanOut) {
    }

    private final List<Map<String, Object>> rows;
    private final String keyColumn;
    private final Class<T> rootType;
    private final List<Child> children = new ArrayList<>();
    private Stats stats;

    private JoinAssembler(List<Map<String, Object>> rows, String keyColumn, Class<T> rootType) {
        if (rows == null) {
            throw new SqlSpecException("join 结果不能为 null");
        }
        this.rows = rows;
        this.keyColumn = keyColumn;
        this.rootType = rootType;
    }

    /**
     * 开始组装一份扁平 join 结果。
     *
     * <p>对输入有三点要求：
     * <ol>
     *   <li>{@code rows} 里每一行都必须包含主键列 {@code keyColumn}，否则该行被丢弃；</li>
     *   <li>子表列必须起别名（{@code rel_id} 而不是 {@code id}），否则与主表同名列冲突；</li>
     *   <li>行的顺序不重要——组装按主键分组，不依赖排序。</li>
     * </ol>
     *
     * @param rows      扁平结果，每行一个 Map（key 为小写列名）
     * @param keyColumn 主表主键列名，用于分组
     * @param rootType  主对象类型，需要无参构造
     * @param <T>       主对象类型
     * @return 组装器，链式声明子集合后再调 {@link #assemble()}
     */
    public static <T> JoinAssembler<T> of(List<Map<String, Object>> rows, String keyColumn, Class<T> rootType) {
        return new JoinAssembler<>(rows, keyColumn, rootType);
    }

    /** 一对多：子表列名与子对象字段同名（不推荐，容易与主表冲突） */
    public <C> JoinAssembler<T> many(String field, Class<C> childType, String childIdColumn) {
        return many(field, childType, childIdColumn, "");
    }

    /** 一对多：子表列带前缀，组装时剥掉前缀再映射 */
    public <C> JoinAssembler<T> many(String field, Class<C> childType, String childIdColumn, String prefix) {
        children.add(new Child(field, childType, childIdColumn, prefix, true));
        return this;
    }

    /** 一对一：只取第一行非空的子对象 */
    public <C> JoinAssembler<T> one(String field, Class<C> childType, String childIdColumn, String prefix) {
        children.add(new Child(field, childType, childIdColumn, prefix, false));
        return this;
    }

    /**
     * 执行组装。
     *
     * <p>行为约定：
     * <ul>
     *   <li>按主键分组，主对象取该组第一行；</li>
     *   <li>子对象按子表主键去重——join 会把主表行重复 N 次，不去重就会出现重复子项；</li>
     *   <li>子表没有数据时给<b>空集合</b>而不是 null，前端不需要判空；</li>
     *   <li>一对一（{@link #one}）取第一个非空子对象，没有则为 null。</li>
     * </ul>
     *
     * @return 主对象列表，顺序与首次出现顺序一致
     * @throws SqlSpecException 主对象缺无参构造，或声明的字段不存在/类型不兼容
     */
    public List<T> assemble() {
        Map<Object, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = value(row, keyColumn);
            if (key == null) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<T> out = new ArrayList<>(groups.size());
        int childCount = 0;
        for (List<Map<String, Object>> group : groups.values()) {
            T root = BeanMapper.fromMap(strip(group.get(0), null), rootType);
            out.add(root);
            for (Child c : children) {
                List<Object> items = new ArrayList<>();
                Set<Object> seen = new LinkedHashSet<>();
                for (Map<String, Object> row : group) {
                    Object id = value(row, c.idColumn());
                    if (id == null || !seen.add(id)) {
                        continue;
                    }
                    items.add(BeanMapper.fromMap(strip(row, c.prefix()), c.type()));
                }
                childCount += items.size();
                if (c.many()) {
                    BeanMapper.setByName(root, c.field(), items);
                } else {
                    BeanMapper.setByName(root, c.field(), items.isEmpty() ? null : items.get(0));
                }
            }
        }
        double fanOut = out.isEmpty() ? 0 : (double) rows.size() / out.size();
        stats = new Stats(rows.size(), out.size(), childCount, Math.round(fanOut * 100) / 100.0);
        return out;
    }

    /**
     * 上一次组装的统计。
     *
     * <p>{@code fanOut} 是「行数 / 主对象数」，也就是 join 把结果放大了几倍。
     * 它超过 5 就该考虑把子集合拆成第二条 SQL——继续 join 下去，
     * 网络传输和内存都会成为瓶颈，而收益只是少一次往返。
     *
     * @return 统计快照；还没调用过 {@link #assemble()} 时返回 null
     */
    public Stats stats() {
        return stats;
    }

    private static Object value(Map<String, Object> row, String column) {
        Object v = row.get(column);
        if (v == null) {
            v = row.get(column.toLowerCase());
        }
        return v;
    }

    /** 剥前缀：prefix 为 null 表示主对象（原样），为空串表示不改名 */
    private static Map<String, Object> strip(Map<String, Object> row, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return row;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(prefix)) {
                // 剥前缀后放一份，原名再放一份：
                // 前者服务 {id} 这类朴素字段，后者服务 {relType} 这类带语义前缀的字段
                out.put(k.substring(prefix.length()), e.getValue());
                out.put(k, e.getValue());
            }
        }
        return out;
    }
}
