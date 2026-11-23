package io.github.jsonsql.support;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 语句级指标：按「SQL 指纹」归并，回答三个问题——
 * 哪条语句总耗时最高、哪条单次最慢、哪条 P99 最差。
 *
 * <p>为什么按指纹而不是按语句 id 归并：同一个 id 在参数不同的情况下会渲染出
 * 不同的 SQL（分页 offset、{@code type: ident} 拼进来的列名），执行计划也可能不同。
 * 只按 id 看会把这些差异糊在一起。
 *
 * <p>默认开启，可用 {@code -Dsqljson.metrics=false} 关闭；仅统计，不影响执行路径。
 */
public final class SqlMetrics {

    /** 每条语句保留最近多少个耗时样本用于算 P99 */
    private static final int SAMPLES = 512;

    private static final SqlMetrics GLOBAL = new SqlMetrics(fromProperty());

    private final boolean on;
    private final Map<String, Slot> stats = new ConcurrentHashMap<>();

    /** 一条语句的汇总快照 */
    public record Stat(String stmt, String fingerprint, String sampleSql, long calls, long errors,
                       long slowCalls, long totalMs, long maxMs, double avgMs, long p99Ms,
                       int lastRows, long lastAt) {
    }

    /** 全局概览 */
    public record Summary(long calls, long errors, long slowCalls, long totalMs,
                          int statements, long uptimeMs) {
    }

    private SqlMetrics(boolean on) {
        this.on = on;
    }

    /**
     * 全局单例，仓库层默认往这里记。
     *
     * <p>即使通过 {@code -Dsqljson.metrics=false} 关掉采集，返回的仍是同一个实例，
     * 只是 {@code record} 变成空操作——这样调用方不需要到处判空。
     *
     * @return 进程级唯一的指标收集器
     * @see #enabled()
     */
    public static SqlMetrics global() {
        return GLOBAL;
    }

    /**
     * 新建一个独立实例，不与全局共享。
     *
     * <p>用于测试（互不干扰）或多数据源场景（按库分开统计）。
     * 实例是线程安全的，可以跨线程写入。
     *
     * @return 新的、始终启用的收集器
     */
    public static SqlMetrics create() {
        return new SqlMetrics(true);
    }

    private static boolean fromProperty() {
        return !"false".equalsIgnoreCase(System.getProperty("sqljson.metrics", "true"));
    }

    /**
     * 是否真的在采集。
     *
     * <p>关闭时所有公开方法仍可调用，只是数据为空——不会抛异常，
     * 这样「先关指标再排查」不会把监控面板打挂。
     *
     * @return true 表示记录生效
     */
    public boolean enabled() {
        return on;
    }

    public void record(String stmt, String sql, long ms, int rows, boolean ok) {
        if (!on) {
            return;
        }
        String fp = fingerprint(sql);
        Slot s = stats.computeIfAbsent(fp, k -> new Slot(stmt, fp, sql));
        s.add(ms, rows, ok, SqlLog.slowMs());
    }

    /**
     * 按总耗时排序：{@code totalMs} 降序。
     *
     * <p>回答「谁在吃掉数据库时间」。高频但单次很快的语句会排在这里，
     * 它们通常是最值得加索引的对象。
     *
     * @param n 返回条数上限
     * @return 快照列表，最多 n 条；数据不足时返回全部
     */
    public List<Stat> topByTotal(int n) {
        return top(n, Comparator.comparingLong(Stat::totalMs).reversed());
    }

    /**
     * 按平均耗时排序。
     *
     * <p>回答「哪条语句每次都慢」。与 {@link #topByTotal(int)} 的区别是它不受调用次数影响，
     * 低频的慢查询只有在这里才看得见。
     *
     * @param n 返回条数上限
     * @return 快照列表
     */
    public List<Stat> topByAvg(int n) {
        return top(n, Comparator.comparingDouble(Stat::avgMs).reversed());
    }

    /**
     * 按 P99 排序。
     *
     * <p>平均值会把长尾抹平：99 次 5ms 加 1 次 2s，均值只有 25ms，但用户遇到的就是那 1 次。
     * P99 用最近 512 个样本计算，能看出「偶发卡顿」是不是集中在某条语句上。
     *
     * @param n 返回条数上限
     * @return 快照列表
     */
    public List<Stat> topByP99(int n) {
        return top(n, Comparator.comparingLong(Stat::p99Ms).reversed());
    }

    /**
     * 按调用次数排序。
     *
     * <p>回答「谁被调得最频繁」。次数高且耗时不低的语句，优化收益最大；
     * 次数高但很快的（如主键查询）通常不需要动。
     *
     * @param n 返回条数上限
     * @return 快照列表
     */
    public List<Stat> topByCalls(int n) {
        return top(n, Comparator.comparingLong(Stat::calls).reversed());
    }

    private List<Stat> top(int n, Comparator<Stat> cmp) {
        List<Stat> list = new ArrayList<>();
        for (Slot s : stats.values()) {
            list.add(s.snapshot());
        }
        list.sort(cmp);
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }

    /**
     * 全局概览：总调用数、错误数、慢查询数、总耗时、语句种类数。
     *
     * <p>适合直接接到健康检查或监控面板上，一眼看出「有没有异常」。
     *
     * @return 汇总快照
     */
    public Summary summary() {
        long calls = 0;
        long errors = 0;
        long slow = 0;
        long total = 0;
        for (Slot s : stats.values()) {
            calls += s.calls.sum();
            errors += s.errors.sum();
            slow += s.slowCalls.sum();
            total += s.totalMs.sum();
        }
        return new Summary(calls, errors, slow, total, stats.size(), System.currentTimeMillis());
    }

    /**
     * 清空所有统计。
     *
     * <p>只在明确知道自己在做什么时调用：清空后 P99 需要重新积累样本，
     * 刚清空时看到的数字没有参考价值。通常用于压测前后、或按时间窗口重置。
     */
    public void reset() {
        stats.clear();
    }

    /**
     * SQL 指纹：小写、折叠空白、把字符串与数字字面量替换成 {@code ?}。
     *
     * <p>注意这里不动标识符——{@code order by age} 与 {@code order by id}
     * 会保留为两个指纹，因为它们本来就是不同的执行计划。
     */
    public static String fingerprint(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                inQuote = !inQuote;
                sb.append("?'");
                continue;
            }
            if (inQuote) {
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                continue;
            }
            sb.append(Character.toLowerCase(ch));
        }
        // 独立数字 → ?（不动紧贴标识符的数字，避免把 t_user1 也换掉）
        String s = sb.toString()
                .replaceAll("(?<![\\w.?])\\d+(?:\\.\\d+)?(?![\\w])", "?")
                .replaceAll("\\?\\?", "?");
        return s.trim();
    }

    /** 单条语句的可变统计；只在 computeIfAbsent 里创建 */
    private static final class Slot {
        private final String stmt;
        private final String fingerprint;
        private final String sampleSql;
        private final LongAdder calls = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder slowCalls = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private volatile long maxMs;
        private volatile int lastRows;
        private volatile long lastAt;
        private final long[] samples = new long[SAMPLES];
        private int cursor;

        private Slot(String stmt, String fingerprint, String sampleSql) {
            this.stmt = stmt;
            this.fingerprint = fingerprint;
            this.sampleSql = sampleSql;
        }

        synchronized void add(long ms, int rows, boolean ok, long slowMs) {
            calls.increment();
            if (!ok) {
                errors.increment();
            }
            if (ms >= slowMs) {
                slowCalls.increment();
            }
            totalMs.add(ms);
            if (ms > maxMs) {
                maxMs = ms;
            }
            samples[cursor] = ms;
            cursor = (cursor + 1) % SAMPLES;
            lastRows = rows;
            lastAt = System.currentTimeMillis();
        }

        synchronized long p99() {
            int n = (int) Math.min(calls.sum(), SAMPLES);
            if (n == 0) {
                return 0;
            }
            long[] copy = new long[n];
            System.arraycopy(samples, 0, copy, 0, n);
            java.util.Arrays.sort(copy);
            int idx = (int) Math.min(n - 1L, Math.ceil(n * 0.99) - 1);
            return copy[Math.max(0, idx)];
        }

        Stat snapshot() {
            long c = calls.sum();
            return new Stat(stmt, fingerprint, sampleSql, c, errors.sum(), slowCalls.sum(),
                    totalMs.sum(), maxMs, c == 0 ? 0 : Math.round((double) totalMs.sum() / c * 10) / 10.0,
                    p99(), lastRows, lastAt);
        }
    }
}
