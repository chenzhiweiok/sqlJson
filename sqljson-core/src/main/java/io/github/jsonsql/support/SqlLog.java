package io.github.jsonsql.support;

import io.github.jsonsql.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * 框架内统一日志出口。
 *
 * <p>约定：
 * <ul>
 *   <li>DEBUG：每条语句的编译结果（SQL + 参数）与执行耗时</li>
 *   <li>INFO：写操作（insert / update / delete）与分页 count</li>
 *   <li>WARN：慢 SQL（阈值由 -Dsqljson.slowMs 控制，默认 200ms）</li>
 *   <li>ERROR：执行失败，带语句名与耗时</li>
 * </ul>
 */
public final class SqlLog {

    private static final Logger LOG = LoggerFactory.getLogger("io.github.jsonsql");

    /** 参数里单个值的最大显示长度，避免日志爆掉 */
    private static final int MAX_VALUE = 120;

    private SqlLog() {
    }

    public static long slowMs() {
        return Long.getLong("sqljson.slowMs", 200L);
    }

    /** 编译结果：只在 debug 级别输出 */
    public static void compiled(String stmt, String sql, List<Object> args) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("[{}] 编译 {} | args={}", stmt, oneLine(sql), args(args));
        }
    }

    public static void executed(String stmt, String sql, List<Object> args, long ms, Object result) {
        long slow = slowMs();
        int rows = rows(stmt, result);

        if (ms >= slow) {
            LOG.warn("[{}] 慢 SQL {}ms rows={} | {} | args={}", stmt, ms, rows, oneLine(sql), args(args));
            return;
        }
        if (LOG.isDebugEnabled()) {
            // 编译阶段已经打过 SQL，这里只补耗时与行数，避免重复
            LOG.debug("[{}] {}ms rows={}", stmt, ms, rows);
        } else if (LOG.isInfoEnabled() && isWrite(stmt)) {
            LOG.info("[{}] {}ms rows={}", stmt, ms, rows);
        }
    }

    public static void failed(String stmt, String sql, long ms, RuntimeException e) {
        LOG.error("[{}] 失败 {}ms | {} | {}", stmt, ms, oneLine(sql), e.getMessage());
    }

    /** insert/update/delete 这类写操作，INFO 级别也打一条，便于审计 */
    private static boolean isWrite(String stmt) {
        String s = stmt == null ? "" : stmt.toLowerCase();
        return s.endsWith(".insert") || s.endsWith(".updatebyid")
                || s.endsWith(".deletebyid") || s.contains("insert")
                || s.contains("update") || s.contains("delete");
    }

    private static int rows(String stmt, Object result) {
        if (result == null) {
            return -1;
        }
        // insert 的返回值是自增主键，不是行数，统一记 1
        if (result instanceof Number && stmt != null && stmt.toLowerCase().endsWith(".insert")) {
            return 1;
        }
        if (result instanceof Collection<?> c) {
            return c.size();
        }
        if (result instanceof PageResult<?> p) {
            return p.list().size();
        }
        if (result instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    private static String oneLine(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static String args(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(show(args.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String show(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof byte[] b) {
            return "<bytes:" + b.length + ">";
        }
        String s = String.valueOf(v);
        if (s.length() > MAX_VALUE) {
            return s.substring(0, MAX_VALUE) + "…";
        }
        return s;
    }
}
