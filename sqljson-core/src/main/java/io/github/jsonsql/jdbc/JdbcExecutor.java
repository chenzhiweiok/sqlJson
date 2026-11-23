package io.github.jsonsql.jdbc;

import io.github.jsonsql.mapping.BeanMapper;
import io.github.jsonsql.support.SqlLog;
import io.github.jsonsql.support.SqlMetrics;
import io.github.jsonsql.support.TxContext;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * JDBC 执行层：只管「在连接上跑一条语句，把结果拿回来」。
 *
 * <p>它不知道实体、注解、SQL 从哪来；输入是 {@code sql + args}，输出是行数、主键、对象列表。
 * 计时、日志、指标、参数绑定、连接获取都在这一层，仓库层因此不需要碰
 * {@code PreparedStatement}——这是这次拆分最直接的收益：想换连接池或加埋点，只改这里。
 *
 * <p><b>连接从哪来</b>：{@link TxContext}。事务内复用当前线程的连接，事务外借一个用完就还。
 * 所以同一个 {@code tx} 块里多个仓库、多次调用，用的是同一个连接，
 * 这也是 {@code tx} 能跨仓库生效的原因。
 *
 * <p><b>线程安全</b>：实例本身只持有 {@code DataSource}，可被多线程共享；
 * 连接与事务状态在 {@link TxContext} 里按线程隔离。
 */
public final class JdbcExecutor {

    private final DataSource dataSource;

    /**
     * @param dataSource 数据源，任何 {@code javax.sql.DataSource} 实现都可以
     */
    public JdbcExecutor(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
    }

    /** @return 底层数据源，事务入口需要它 */
    public DataSource dataSource() {
        return dataSource;
    }

    // ---------------- 事务 ----------------

    /**
     * 在事务里执行，抛异常整体回滚。
     *
     * <p>委托给 {@link TxContext}，不在这一层维护状态——事务是「连接的作用域」，
     * 不是执行器的属性，放这里会被下一个请求继承。
     *
     * @param body 事务体
     */
    public void tx(Runnable body) {
        TxContext.run(dataSource, body);
    }

    /**
     * 带返回值的事务块。
     *
     * @param body 事务体
     * @param <R>  返回值类型
     * @return 事务体的返回值
     */
    public <R> R txGet(Supplier<R> body) {
        return TxContext.call(dataSource, body);
    }

    /** @return 当前线程是否在事务中 */
    public boolean inTransaction() {
        return TxContext.inTransaction();
    }

    // ---------------- 写 ----------------

    /**
     * 执行一条更新/删除语句。
     *
     * @param stmt 语句标识，只用于日志与指标
     * @param s    语句与参数
     * @return 受影响行数
     */
    public int update(String stmt, SqlStatement s) {
        return update(stmt, s.sql(), s.args());
    }

    /**
     * 执行一条更新/删除语句。
     *
     * @param stmt 语句标识，只用于日志与指标
     * @param sql  参数化 SQL
     * @param args 参数
     * @return 受影响行数
     */
    public int update(String stmt, String sql, List<Object> args) {
        List<Object> a = args == null ? List.of() : args;
        return exec(stmt, sql, a, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, a);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * 执行插入并取回自增主键。
     *
     * <p>没取到主键时返回 0，而不是抛异常——某些驱动（或非自增表）本来就不返回，
     * 这不算失败。调用方用 0 判断「没有可回填的主键」。
     *
     * @param stmt 语句标识
     * @param sql  参数化 SQL
     * @param args 参数
     * @return 生成的主键；取不到时为 0
     */
    public long insertReturningKey(String stmt, String sql, List<Object> args) {
        List<Object> a = args == null ? List.of() : args;
        return exec(stmt, sql, a, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, a);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        });
    }

    // ---------------- 读 ----------------

    /**
     * 查询并映射成实体。
     *
     * @param stmt 语句标识
     * @param sql  参数化 SQL
     * @param args 参数
     * @param type 目标类型，需要无参构造
     * @param <T>  目标类型
     * @return 实体列表，无数据时返回空列表
     */
    public <T> List<T> query(String stmt, String sql, List<Object> args, Class<T> type) {
        List<Object> a = args == null ? List.of() : args;
        return exec(stmt, sql, a, c -> {
            List<T> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, a);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(BeanMapper.fromRow(rs, type));
                    }
                }
            }
            return rows;
        });
    }

    /**
     * 查询并返回 Map 列表，列名统一小写。
     *
     * <p>给聚合、join、动态列用——这类结果没有稳定实体，硬映射只会多出一堆被忽略的字段。
     *
     * @param stmt 语句标识
     * @param sql  参数化 SQL
     * @param args 参数
     * @return 每行一个 Map，保持 SQL 的列顺序
     */
    public List<Map<String, Object>> queryMaps(String stmt, String sql, List<Object> args) {
        List<Object> a = args == null ? List.of() : args;
        return exec(stmt, sql, a, c -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, a);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(BeanMapper.rowToMap(rs));
                    }
                }
            }
            return rows;
        });
    }

    /**
     * 取单值计数，读第一行第一列。
     *
     * @param stmt 语句标识
     * @param sql  参数化 SQL，期望返回一行一列
     * @param args 参数
     * @return 计数值；没有结果行时为 0
     */
    public long count(String stmt, String sql, List<Object> args) {
        List<Object> a = args == null ? List.of() : args;
        return exec(stmt, sql, a, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, a);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    // ---------------- 内部 ----------------

    /** 统一入口：拿连接 → 执行 → 计时 → 记日志与指标；失败也记录后再抛 */
    private <R> R exec(String stmt, String sql, List<Object> args, TxContext.ConnWork<R> work) {
        long start = System.nanoTime();
        try {
            R result = TxContext.work(dataSource, work);
            long ms = elapsedMs(start);
            SqlLog.executed(stmt, sql, args, ms, result);
            SqlMetrics.global().record(stmt, sql, ms, rowsOf(stmt, result), true);
            return result;
        } catch (RuntimeException e) {
            long ms = elapsedMs(start);
            SqlLog.failed(stmt, sql, ms, e);
            SqlMetrics.global().record(stmt, sql, ms, -1, false);
            throw e;
        }
    }

    /** 行数口径与日志保持一致：集合取 size，insert 固定 1（返回值是主键，不是行数） */
    private static int rowsOf(String stmt, Object result) {
        if (result == null) {
            return -1;
        }
        if (result instanceof Collection<?> c) {
            return c.size();
        }
        if (result instanceof Number n) {
            return stmt != null && stmt.toLowerCase().endsWith(".insert") ? 1 : n.intValue();
        }
        return -1;
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000L;
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v instanceof LocalDateTime t) {
                ps.setTimestamp(i + 1, Timestamp.valueOf(t));
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }
}
