package io.github.jsonsql.engine;
import java.util.Locale;

/**
 * 逻辑删除的 SQL 改写：给查询与更新补上 {@code <col> = 0}，不碰 INSERT 和 DELETE。
 *
 * <p>只处理顶层 WHERE（跳过括号与字符串），因此对子查询里的 WHERE 不会误插。
 * 若语句里已经出现了该列的等值条件，认为调用方自己管了，不再追加。
 */
public final class SoftDeleteRewriter {

    private SoftDeleteRewriter() {
    }

    /**
     * @param sql    SELECT / UPDATE 语句
     * @param column 逻辑删除列名
     */
    public static String filter(String sql, String column) {
        if (sql == null || column == null || column.isBlank()) {
            return sql;
        }
        String head = firstWord(sql);
        if (!"select".equals(head) && !"update".equals(head)) {
            return sql;
        }
        if (alreadyHandled(sql, column)) {
            return sql;
        }

        int where = find(sql, "where");
        if (where >= 0) {
            int after = where + "where".length();
            return sql.substring(0, after) + " (" + column + " = 0) and " + sql.substring(after);
        }

        int insertAt = firstOf(sql, "order by", "group by", "having", "limit", "fetch first", "for update");
        if (insertAt < 0) {
            insertAt = sql.length();
        }
        String prefix = sql.substring(0, insertAt).stripTrailing();
        String suffix = sql.substring(insertAt);
        return prefix + " where " + column + " = 0 " + suffix;
    }

    /** 判断语句的主体类型：select / update / delete / insert */
    static String firstWord(String sql) {
        String s = sql.stripLeading().toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        return s.substring(0, i);
    }

    private static boolean alreadyHandled(String sql, String column) {
        String lower = sql.toLowerCase(Locale.ROOT);
        String col = column.toLowerCase(Locale.ROOT);
        return lower.matches("(?s).*\\b" + col + "\\s*=\\s*[\\d?].*")
                || lower.matches("(?s).*\\b" + col + "\\s+is\\s+null.*");
    }

    private static int firstOf(String sql, String... keywords) {
        int best = -1;
        for (String kw : keywords) {
            int i = find(sql, kw);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    /** 只在顶层（括号深度 0、不在字符串里）匹配关键字，且要求词边界 */
    static int find(String sql, String keyword) {
        String lower = sql.toLowerCase(Locale.ROOT);
        String kw = keyword.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = lower.indexOf(kw, from);
            if (i < 0) {
                return -1;
            }
            boolean leftOk = i == 0 || !isWord(lower.charAt(i - 1));
            int end = i + kw.length();
            boolean rightOk = end >= lower.length() || !isWord(lower.charAt(end));
            if (leftOk && rightOk && isTopLevel(sql, i)) {
                return i;
            }
            from = i + 1;
        }
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isTopLevel(String sql, int index) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < index; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
        }
        return depth == 0 && !quoted;
    }
}
