package io.github.jsonsql;
/**
 * 当前操作人，供审计字段（created_by / updated_by）取值。
 *
 * <p>框架本身不绑定任何认证方案：不引 Spring Security，也不知道 Session 长什么样。
 * 它只提供一个线程上下文，谁登录、怎么登录由上层决定：
 *
 * <pre>{@code
 * // Web 层：请求进入时设置，结束时清理
 * CurrentUser.set("qiu");
 * try {
 *     repo.insert(user);          // created_by 自动填 qiu
 * } finally {
 *     CurrentUser.clear();        // 必须清理，否则线程复用会串号
 * }
 * }</pre>
 *
 * <p><b>线程安全</b>：基于 {@link ThreadLocal}，每个线程一份。这也意味着两个限制：
 * <ul>
 *   <li>必须在<b>同一个线程</b>里设置与使用。异步任务、线程池切换后取不到值，
 *       会退化成 {@code "system"}；</li>
 *   <li>必须清理。Servlet 容器复用线程，漏掉 {@link #clear()} 会让下一个请求
 *       带着上一个用户的名字写数据——这类错误在日志里看不出来，只有审计时才发现。</li>
 * </ul>
 *
 * <p>参数为空或空白时 {@link #get()} 返回 {@code "system"} 而不是 null，
 * 这样审计字段不会因为没登录就写入 null。
 */
public final class CurrentUser {

    /** 未设置时的兜底值；用常量而不是 null，避免调用方到处判空 */
    private static final String ANONYMOUS = "system";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    /**
     * 设置当前线程的操作人。
     *
     * @param user 用户名；传 null 等同于清除
     */
    public static void set(String user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }

    /**
     * 读取当前操作人。
     *
     * @return 已设置的用户名；未设置或为空白时返回 {@code "system"}
     */
    public static String get() {
        String u = HOLDER.get();
        return u == null || u.isBlank() ? ANONYMOUS : u;
    }

    /**
     * 清理当前线程的操作人。
     *
     * <p>Web 层应在 {@code finally} 或拦截器的 afterCompletion 里调用，
     * 这是线程池环境下唯一可靠的清理时机。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 以指定身份执行一段逻辑，结束后恢复原来的值（原来没有则清除）。
     *
     * <p>用于后台任务、定时任务这类没有登录上下文的场景：
     * <pre>{@code
     * CurrentUser.runAs("job:settle", () -> repo.insert(record));
     * }</pre>
     *
     * @param user 临时身份
     * @param body 要执行的逻辑，不能为 null
     */
    public static void runAs(String user, Runnable body) {
        String old = HOLDER.get();
        HOLDER.set(user);
        try {
            body.run();
        } finally {
            if (old == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(old);
            }
        }
    }
}
