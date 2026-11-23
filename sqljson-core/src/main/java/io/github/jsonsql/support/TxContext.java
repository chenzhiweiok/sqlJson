package io.github.jsonsql.support;

import io.github.jsonsql.SqlSpecException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Supplier;

/**
 * 事务上下文：线程内共享同一个 Connection。
 *
 * <ul>
 *   <li>最外层 {@code tx} 负责 begin / commit / rollback / 关闭连接</li>
 *   <li>嵌套 {@code tx} 用 Savepoint 隔离，内层失败只回滚到自己的保存点</li>
 *   <li>同一个线程里不同实体的仓库共用同一个连接，跨实体操作天然原子</li>
 * </ul>
 */
public final class TxContext {

    /**
     * 连接来源的扩展点。默认直接用 DataSource；接 Spring 时换成一个
     * 走 DataSourceUtils 的实现，就能加入 Spring 的 @Transactional。
     */
    public interface ConnectionSource {
        Connection acquire(DataSource ds) throws SQLException;

        void release(Connection c, DataSource ds) throws SQLException;

        /** 外部（如 Spring）是否已开启事务 */
        default boolean inTransaction(DataSource ds) {
            return false;
        }
    }

    private static volatile ConnectionSource source;

    /** 装一个连接来源，通常应用启动时调用一次 */
    public static void use(ConnectionSource connectionSource) {
        source = connectionSource;
    }

    /**
     * 清空当前线程的事务状态与自定义连接源。
     *
     * <p>给测试用：同一个线程里跑多个用例时，避免上一个用例的事务状态泄漏到下一个。
     * 生产代码不该调它——清理时机应该由事务边界决定，不是随手清。
     */
    public static void reset() {
        source = null;
    }

    /** 当前线程的事务状态；null 表示不在事务里 */
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TxContext() {
    }

    private static final class State {
        final Connection connection;

        State(Connection connection) {
            this.connection = connection;
        }
    }

    /** 需要连接的回调，允许抛 SQLException，由框架统一包成 SqlSpecException */
    public interface ConnWork<R> {
        R apply(Connection c) throws SQLException;
    }

    /**
     * 取连接执行：在事务里复用当前连接，不在事务里开一个用完就关。
     */
    public static <R> R work(DataSource ds, ConnWork<R> fn) {
        State s = CURRENT.get();
        if (s != null) {
            try {
                return fn.apply(s.connection);
            } catch (SQLException e) {
                throw new SqlSpecException("执行失败: " + e.getMessage());
            }
        }

        ConnectionSource src = source;
        if (src != null) {
            Connection c = null;
            try {
                c = src.acquire(ds);
                return fn.apply(c);
            } catch (SQLException e) {
                throw new SqlSpecException("执行失败: " + e.getMessage());
            } finally {
                if (c != null) {
                    try {
                        src.release(c, ds);
                    } catch (SQLException ignored) {
                        // 归还失败不影响业务结果
                    }
                }
            }
        }

        try (Connection c = ds.getConnection()) {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new SqlSpecException("执行失败: " + e.getMessage());
        }
    }

    /** 开启事务；已在事务内则挂一个 Savepoint */
    public static <R> R call(DataSource ds, Supplier<R> body) {
        ConnectionSource src = source;
        if (src != null && src.inTransaction(ds)) {
            // 外部（Spring）已开启事务，交给它管理提交/回滚
            return body.get();
        }

        State existing = CURRENT.get();
        if (existing != null) {
            Savepoint sp = null;
            try {
                sp = existing.connection.setSavepoint();
            } catch (SQLException ignored) {
                // 不支持保存点就退化为"内层失败整体失败"
            }
            try {
                return body.get();
            } catch (RuntimeException e) {
                try {
                    if (sp != null) {
                        existing.connection.rollback(sp);
                    }
                } catch (SQLException ignored) {
                    // 保留原始异常
                }
                throw e;
            }
        }

        Connection c = null;
        try {
            c = ds.getConnection();
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            CURRENT.set(new State(c));
            try {
                R result = body.get();
                c.commit();
                return result;
            } catch (RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // 保留原始异常
                }
                throw e;
            } finally {
                CURRENT.remove();
                try {
                    c.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // 连接即将关闭
                }
            }
        } catch (SQLException e) {
            throw new SqlSpecException("事务开启失败: " + e.getMessage());
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // 关闭失败不影响结果
                }
            }
        }
    }

    public static void run(DataSource ds, Runnable body) {
        call(ds, () -> {
            body.run();
            return null;
        });
    }

    /**
     * 当前线程是否持有活动事务。
     *
     * <p>接入 Spring 后，这里同时反映「框架自己开的事务」和「Spring 的 @Transactional」，
     * 因为判断委托给了 {@link ConnectionSource#inTransaction(DataSource)}。
     *
     * @return true 表示当前线程在事务中
     */
    public static boolean inTransaction() {
        return CURRENT.get() != null;
    }
}
