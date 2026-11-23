package io.github.jsonsql;
import java.util.List;

/**
 * 分页结果。
 *
 * <p>{@link #list()} 永不为 {@code null}：没有数据时是空列表，调用方不需要到处判空，
 * 也就不会写出「查询成功但页面报 NPE」这类问题。
 *
 * <p>{@code total} 来自 count 语句（或从主 SQL 推导），与 {@code list} 的条数无关——
 * 后者是当前页的行数。两者分开保存，是为了让前端能画页码而不必再发一次请求。
 *
 * @param list     当前页数据，非 null
 * @param total    满足条件的总行数，可能为 0
 * @param pageNo   当前页码，从 1 开始
 * @param pageSize 每页条数
 * @param <T>      行类型
 */
public record PageResult<T>(List<T> list, long total, int pageNo, int pageSize) {

    /**
     * 总页数，向上取整。
     *
     * @return {@code pageSize} 非法（≤0）时返回 0，避免除零；{@code total} 为 0 时返回 0
     */
    public int pages() {
        return pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
    }

    /** 是否还有下一页。前端据此决定「下一页」按钮是否可点 */
    public boolean hasNext() {
        return pageNo < pages();
    }

    /** 日志与调试用的紧凑表示，不用于序列化（JSON 走 record 的字段） */
    @Override
    public String toString() {
        return "PageResult{本页=" + list.size() + ", total=" + total
                + ", pageNo=" + pageNo + ", pageSize=" + pageSize + ", pages=" + pages() + "}";
    }
}
