package io.github.jsonsql.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 批量主键集合：去重、排序、分块三件事的统一出口。
 *
 * <p><b>为什么单独成类</b>：{@code deleteBatch / restoreBatch / findByIds / updateFields}
 * 都要处理同一组边界，而这组边界每一条都「不做也不会报错，只会在线上以别的形式出现」：
 * <ul>
 *   <li><b>去重</b>：{@code IN (1,1,1)} 不报错，但会让批量更新多绑几次参数，
 *       也会让「影响行数」这类统计对不上直觉；</li>
 *   <li><b>升序</b>：并发批量更新同一批 id 时，如果两个事务的加锁顺序相反，
 *       即使操作互不冲突也可能互相等待成环。统一按主键升序加锁，
 *       死锁会退化成简单的等待——这是数据库层面的经验做法，代码里只花一行；</li>
 *   <li><b>分块</b>：Oracle 的 {@code IN} 上限是 1000，其它库虽然没有硬上限，
 *       但单条 SQL 的文本长度受 {@code max_allowed_packet} / 解析开销约束，
 *       一次塞十万个 id 只会把连接卡住。</li>
 * </ul>
 *
 * <p><b>只处理「主键值集合」</b>：它不是通用的集合工具，不做去重以外的元素变换。
 * 元素为 null 会被丢弃——{@code IN (null)} 在 SQL 里既不匹配任何行也不报错，
 * 留着只会让人误以为「这个 id 被处理过了」。
 *
 * <p><b>排序的边界</b>：只有元素全是 {@link Number} 或全是 {@link CharSequence} 时才排序，
 * 其它情况（混合类型、自定义主键对象）保持插入顺序。排序失败比不排序更危险，
 * 所以这里宁可不排，也不去猜比较规则。
 *
 * <p><b>不可变</b>：构建完成后只读，可安全地在线程间传递。
 */
public final class IdSet {

    /** 默认分块大小：500 是「Oracle IN 上限 1000」的一半，给调用方留出嵌套条件的余量 */
    public static final int DEFAULT_CHUNK = 500;

    private final List<Object> ids;
    private final int chunkSize;

    private IdSet(List<Object> ids, int chunkSize) {
        this.ids = ids;
        this.chunkSize = chunkSize;
    }

    /**
     * 用默认分块大小构建。
     *
     * @param raw 原始主键集合，可为 null 或空
     * @return 规整后的集合；null / 空集合返回空实例，调用方可用 {@link #isEmpty()} 短路
     */
    public static IdSet of(Collection<?> raw) {
        return of(raw, DEFAULT_CHUNK);
    }

    /**
     * 指定分块大小构建。
     *
     * @param raw       原始主键集合
     * @param chunkSize 每块上限；小于 1 时按 {@link #DEFAULT_CHUNK}
     * @return 规整后的集合
     */
    public static IdSet of(Collection<?> raw, int chunkSize) {
        int size = chunkSize < 1 ? DEFAULT_CHUNK : chunkSize;
        if (raw == null || raw.isEmpty()) {
            return new IdSet(List.of(), size);
        }
        LinkedHashSet<Object> unique = new LinkedHashSet<>(raw.size() * 2);
        for (Object id : raw) {
            if (id != null) {
                unique.add(id);
            }
        }
        List<Object> sorted = new ArrayList<>(unique);
        sortIfComparable(sorted);
        return new IdSet(List.copyOf(sorted), size);
    }

    /** @return 是否一个 id 都没有（含原始集合为 null 的情况） */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /** @return 去重排序后的 id 数量 */
    public int size() {
        return ids.size();
    }

    /**
     * 去重排序后的 id 列表。
     *
     * <p>注意顺序已经变了：需要「按调用方传入顺序返回」的场景（例如 {@code findByIds}）
     * 应该自己保留原始顺序，用这里的结果做查询、再按原始顺序重排结果。
     *
     * @return 不可变列表
     */
    public List<Object> values() {
        return ids;
    }

    /**
     * 按 {@code chunkSize} 切片。
     *
     * @return 每块一个列表；空集合返回空列表（不是「一个空块」）
     */
    public List<List<Object>> chunks() {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<List<Object>> out = new ArrayList<>((ids.size() + chunkSize - 1) / chunkSize);
        for (int i = 0; i < ids.size(); i += chunkSize) {
            out.add(ids.subList(i, Math.min(i + chunkSize, ids.size())));
        }
        return out;
    }

    /**
     * 全为数字时按数值升序，全为字符串时按字典序，其它保持原序。
     *
     * <p>为什么不直接 {@code Collections.sort}：元素类型由调用方决定，
     * 混合类型会抛 {@link ClassCastException}，而「排序失败」不该让一次删除操作失败。
     */
    private static void sortIfComparable(List<Object> list) {
        if (list.size() < 2) {
            return;
        }
        if (list.stream().allMatch(Number.class::isInstance)) {
            list.sort(Comparator.comparingLong(o -> ((Number) o).longValue()));
        } else if (list.stream().allMatch(CharSequence.class::isInstance)) {
            list.sort(Comparator.comparing(Object::toString));
        }
    }

    @Override
    public String toString() {
        return "IdSet(" + ids.size() + (ids.isEmpty() ? "" : "=" + ids) + ")";
    }
}
