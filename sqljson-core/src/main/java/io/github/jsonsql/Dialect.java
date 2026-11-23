package io.github.jsonsql;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方言：目前只处理分页写法差异。框架自身生成的永远是 {@code LIMIT n [OFFSET m]}，
 * 由方言翻译成目标库的语法。
 */
public interface Dialect {

    Pattern PAGING = Pattern.compile("(?i)\\s+limit\\s+(\\d+)(?:\\s+offset\\s+(\\d+))?\\s*$");

    /**
     * 方言标识，用于日志与排查（「这条 SQL 是按哪个方言改写的」）。
     *
     * @return 小写名称，如 {@code mysql / postgres / oracle / dm}
     */
    String name();

    /**
     * 把 {@code LIMIT n OFFSET m} 翻译成本方言写法。
     *
     * <p>只处理语句末尾的分页子句：正则 {@link #PAGING} 不匹配时原样返回，
     * 所以子查询里的 limit、或者手写了别的分页形式的语句不会被误改。
     *
     * @param sql 已渲染、已改写过的 SQL
     * @return 目标库能执行的 SQL
     */
    default String rewritePaging(String sql) {
        return sql;
    }

    /**
     * MySQL / MariaDB：{@code LIMIT n OFFSET m} 直接可用，不做改写。
     *
     * <p>H2、SQLite、PolarDB 也用这个。
     *
     * @return 方言实例
     */
    static Dialect mysql() {
        return new Simple("mysql");
    }

    /**
     * PostgreSQL：语法与 MySQL 一致，单独给一个名字是为了日志里能看出实际用的是哪个库。
     *
     * @return 方言实例
     */
    static Dialect postgres() {
        return new Simple("postgres");
    }

    /** Oracle 12c+ 与达梦 8 都支持 OFFSET ... FETCH */
    static Dialect oracleLike(String name) {
        return new OracleLike(name);
    }

    /**
     * 按名字取方言，用于配置化场景（{@code sqljson.dialect=dm}）。
     *
     * <p>认不出的名字会退回 MySQL 而不是抛异常——启动期因为一个拼写错误就起不来，
     * 比退化成「分页可能不对」更难排查。真要知道有没有匹配上，看启动日志里的方言名。
     *
     * @param name 方言名，大小写不敏感；null 视为 mysql
     * @return 方言实例，永不返回 null
     */
    static Dialect of(String name) {
        String n = name == null ? "mysql" : name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "postgres", "postgresql", "pg" -> postgres();
            case "oracle" -> oracleLike("oracle");
            case "dm", "dameng" -> oracleLike("dm");
            default -> mysql();
        };
    }

    final class Simple implements Dialect {
        private final String name;

        Simple(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }

    final class OracleLike implements Dialect {
        private final String name;

        OracleLike(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String rewritePaging(String sql) {
            Matcher m = PAGING.matcher(sql);
            if (!m.find()) {
                return sql;
            }
            int limit = Integer.parseInt(m.group(1));
            String offset = m.group(2);
            String head = sql.substring(0, m.start());
            if (offset != null) {
                return head + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            }
            return head + " FETCH FIRST " + limit + " ROWS ONLY";
        }
    }
}
