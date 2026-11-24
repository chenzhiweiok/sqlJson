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
     * 是否支持原生 upsert（「有则更新、无则插入」）。
     *
     * <p>默认 false：方言不声明支持，框架就走降级路径（先查存在性，再分流 update / insert）。
     * 宁可多一次查询，也不要生成一条对方库不认的 SQL——那种错误只在生产的第一批数据上暴露。
     *
     * @return true 表示 {@link #upsertStatement} 可用
     */
    default boolean supportsUpsert() {
        return false;
    }

    /**
     * 生成 upsert 语句。
     *
     * <p>默认抛异常，让「忘了实现」在第一次调用时暴露，而不是静默拼出一条错 SQL。
     *
     * @param insertSql     已生成的 {@code insert into t (...) values (...)} 语句
     * @param table         表名
     * @param columns       本块涉及的列
     * @param keyColumns    冲突判定列（通常就是主键）
     * @param updateColumns 冲突时要覆盖的列（不含 keyColumns）
     * @param rowCount      本块行数，供需要逐行占位符的方言（H2 MERGE）使用
     * @return 完整 SQL，占位符数量必须与 {@code insertSql} 一致
     */
    default String upsertStatement(String insertSql, String table, java.util.List<String> columns,
                                   java.util.List<String> keyColumns, java.util.List<String> updateColumns,
                                   int rowCount) {
        throw new UnsupportedOperationException(name() + " 不支持原生 upsert");
    }

    /**
     * MySQL / MariaDB：{@code LIMIT n OFFSET m} 直接可用，不做改写。
     *
     * @return 方言实例
     */
    static Dialect mysql() {
        return new MySql();
    }

    /**
     * PostgreSQL：分页语法与 MySQL 一致，upsert 用 {@code on conflict}。
     *
     * <p>单独一个实现而不是复用 Simple，是因为两种库的 upsert 语法完全不同——
     * 共用实现只能退化成「都不支持」。
     *
     * @return 方言实例
     */
    static Dialect postgres() {
        return new Postgres();
    }

    /**
     * H2：分页同 MySQL，upsert 用 {@code merge into ... key(...)}。
     *
     * <p>主要给测试与本地开发用——单元测试跑在 H2 上，没有这条实现就永远只能测降级路径。
     *
     * @return 方言实例
     */
    static Dialect h2() {
        return new H2();
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
            case "h2" -> h2();
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

    /**
     * MySQL / MariaDB。
     *
     * <p>upsert 用 {@code ON DUPLICATE KEY UPDATE col = VALUES(col)}：
     * 这是唯一在 5.7 与 8.x 上都成立的写法。<b>8.0.20 起 {@code VALUES()} 被标记为弃用</b>
     * （仍可用，会有 deprecation warning），官方推荐改成行别名
     * {@code INSERT ... AS new ... SET col = new.col}，但那个语法 8.0.19 才有，
     * 用了就等于放弃 5.7。这里的取舍是「兼容优先」，等确认全线 8.0.19+ 再换。
     */
    final class MySql implements Dialect {
        @Override
        public String name() {
            return "mysql";
        }

        @Override
        public boolean supportsUpsert() {
            return true;
        }

        @Override
        public String upsertStatement(String insertSql, String table, java.util.List<String> columns,
                                      java.util.List<String> keyColumns, java.util.List<String> updateColumns,
                                      int rowCount) {
            StringBuilder sb = new StringBuilder(insertSql).append(" on duplicate key update ");
            for (int i = 0; i < updateColumns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String c = updateColumns.get(i);
                sb.append(c).append(" = values(").append(c).append(')');
            }
            return sb.toString();
        }
    }

    /**
     * PostgreSQL。
     *
     * <p>{@code ON CONFLICT (key) DO UPDATE SET col = EXCLUDED.col} 要求 key 上
     * 有唯一约束或主键，否则数据库直接报错——这一点无法在框架里兜住，
     * 所以表必须有主键（框架装配时本来就要求有主键字段，正好一致）。
     */
    final class Postgres implements Dialect {
        @Override
        public String name() {
            return "postgres";
        }

        @Override
        public boolean supportsUpsert() {
            return true;
        }

        @Override
        public String upsertStatement(String insertSql, String table, java.util.List<String> columns,
                                      java.util.List<String> keyColumns, java.util.List<String> updateColumns,
                                      int rowCount) {
            StringBuilder sb = new StringBuilder(insertSql)
                    .append(" on conflict (").append(String.join(", ", keyColumns)).append(") do update set ");
            for (int i = 0; i < updateColumns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String c = updateColumns.get(i);
                sb.append(c).append(" = excluded.").append(c);
            }
            return sb.toString();
        }
    }

    /**
     * H2：{@code MERGE INTO t (cols) KEY (id) VALUES (...), (...)}。
     *
     * <p>与 MySQL / PG 不同，它不是「在 INSERT 后面挂一段」，而是另一条语句本身，
     * 所以这里要重新拼占位符——行数由 {@code rowCount} 给出。
     */
    final class H2 implements Dialect {
        @Override
        public String name() {
            return "h2";
        }

        @Override
        public boolean supportsUpsert() {
            return true;
        }

        @Override
        public String upsertStatement(String insertSql, String table, java.util.List<String> columns,
                                      java.util.List<String> keyColumns, java.util.List<String> updateColumns,
                                      int rowCount) {
            StringBuilder sb = new StringBuilder("merge into ").append(table)
                    .append(" (").append(String.join(", ", columns)).append(")")
                    .append(" key (").append(String.join(", ", keyColumns)).append(") values ");
            for (int r = 0; r < rowCount; r++) {
                if (r > 0) {
                    sb.append(", ");
                }
                sb.append('(');
                for (int i = 0; i < columns.size(); i++) {
                    sb.append(i > 0 ? ", ?" : "?");
                }
                sb.append(')');
            }
            return sb.toString();
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
