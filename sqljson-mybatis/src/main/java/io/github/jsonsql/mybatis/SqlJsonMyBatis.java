package io.github.jsonsql.mybatis;

import io.github.jsonsql.engine.SqlV2;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 sqljson 的 JSON 语句注册成 MyBatis 的 {@link MappedStatement}。
 *
 * <p>定位说明：这不是"用 sqljson 替代 MyBatis"，而是把 JSON 当作 MyBatis 的 SQL 源。
 * 好处很直接——SQL 依然是 JSON 声明、可生成、可 diff、可热更新；
 * 而执行、事务、缓存、插件、分页拦截全交给已经成熟的 MyBatis，不用自己重造。
 *
 * <pre>{@code
 * SqlV2 engine = SqlV2.load("sqls/sys_user.json");
 * SqlJsonMyBatis.register(configuration, engine, "user", User.class);
 *
 * try (SqlSession session = factory.openSession()) {
 *     List<User> list = session.selectList("user.findPage", Map.of("pageSize", 10, "offset", 0));
 * }
 * }</pre>
 *
 * <p>参数绑定方式：sqljson 在渲染阶段已经拿到全部实参，因此每个 {@code ?}
 * 对应一个内部参数 {@code p0, p1, ...}，通过 {@link BoundSql} 的 parameterObject 传入，
 * MyBatis 不需要再解析 {@code #{}}。
 */
public final class SqlJsonMyBatis {

    private SqlJsonMyBatis() {
    }

    /**
     * 注册一个引擎里的全部语句。
     *
     * @param configuration MyBatis 配置
     * @param engine        sqljson 编译引擎
     * @param namespace     命名空间前缀，最终语句 id 为 {@code namespace.语句名}
     * @param resultType    select 语句的返回类型，可为 null（返回 Map）
     * @return 注册的语句条数
     */
    public static int register(Configuration configuration, SqlV2 engine, String namespace, Class<?> resultType) {
        return register(configuration, engine, namespace, resultType, Map.of());
    }

    /**
     * 注册，并允许按语句名单独指定返回类型。
     *
     * <p>聚合/单值查询请显式指定 {@code Map.class} 并给列起别名（如 {@code COUNT(*) AS cnt}）。
     * 原因是 MyBatis 把简单类型（Long、String）当作"有 TypeHandler 的结果类型"处理，
     * 不会走自动映射，也就无法从任意列名取值。与其猜列名，不如要求别名 + Map，行为可预期。
     */
    public static int register(Configuration configuration, SqlV2 engine, String namespace,
                               Class<?> resultType, Map<String, Class<?>> overrides) {
        int n = 0;
        for (String docId : engine.ids()) {
            String statementName = docId.contains(".")
                    ? docId.substring(docId.lastIndexOf('.') + 1)
                    : docId;
            String id = namespace + "." + statementName;
            String sql = engine.doc(docId).sql();
            SqlCommandType command = commandType(sql);
            Class<?> type = overrides.getOrDefault(statementName, resultType);

            MappedStatement.Builder builder = new MappedStatement.Builder(
                    configuration, id, new TemplateSqlSource(configuration, engine, docId), command);
            builder.statementType(StatementType.PREPARED);
            if (command == SqlCommandType.SELECT && type != null) {
                ResultMap resultMap = new ResultMap.Builder(configuration, id + "-Inline",
                        type, List.of()).build();
                builder.resultMaps(List.of(resultMap));
            }
            configuration.addMappedStatement(builder.build());
            n++;
        }
        return n;
    }

    /** 从 SQL 首词判断命令类型；识别不了的按 SELECT 处理会在执行时报错，所以给 UNKNOWN 抛异常更清晰 */
    static SqlCommandType commandType(String sql) {
        String s = stripLeading(sql);
        int end = 0;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '(') {
            end++;
        }
        String word = s.substring(0, end).toUpperCase(Locale.ROOT);
        return switch (word) {
            case "SELECT", "WITH" -> SqlCommandType.SELECT;
            case "INSERT", "REPLACE" -> SqlCommandType.INSERT;
            case "UPDATE" -> SqlCommandType.UPDATE;
            case "DELETE" -> SqlCommandType.DELETE;
            default -> throw new IllegalArgumentException("无法识别的语句类型: " + word + " | " + sql);
        };
    }

    private static String stripLeading(String sql) {
        int i = 0;
        while (i < sql.length() && (Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == '(')) {
            i++;
        }
        return sql.substring(i);
    }

    /** 每次执行时用实参渲染 SQL，再把结果实参按 p0..pN 交给 MyBatis 绑定 */
    static final class TemplateSqlSource implements SqlSource {

        private final Configuration configuration;
        private final SqlV2 engine;
        private final String docId;

        TemplateSqlSource(Configuration configuration, SqlV2 engine, String docId) {
            this.configuration = configuration;
            this.engine = engine;
            this.docId = docId;
        }

        /**
         * 每次执行时用实参渲染 SQL，再把结果按 {@code p0..pN} 交给 MyBatis 绑定。
         *
         * <p><b>为什么用 additionalParameters 而不是 parameterObject</b>：
         * {@code DefaultParameterHandler} 的取值顺序是
         * 「additionalParameters → parameterObject 是简单类型 → 反射取属性」。
         * 如果把 {@code p0} 塞进 parameterObject，MyBatis 会把它当成一个叫 p0 的
         * <b>实体属性</b>去反射，调用 {@code insert(实体)} 时直接报
         * {@code There is no getter for property named 'p0'}。
         * 放进 additionalParameters 才能既支持 Map 参数，也支持整实体参数。
         *
         * @param parameterObject 调用方传给 SqlSession 的参数：Map 或单个实体
         * @return 参数化 SQL 与已渲染好的实参
         */
        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            Map<String, Object> args = toArgs(parameterObject);
            SqlV2.Prepared prepared = engine.prepare(docId, args);

            List<ParameterMapping> mappings = new ArrayList<>(prepared.args().size());
            for (int i = 0; i < prepared.args().size(); i++) {
                mappings.add(new ParameterMapping.Builder(configuration, "p" + i, Object.class).build());
            }
            // 用 additionalParameters 传参：DefaultParameterHandler 会优先读它，
            // 这样调用方传实体还是 Map 都不影响 p0..pN 的解析
            BoundSql boundSql = new BoundSql(configuration, prepared.sql(), mappings, parameterObject);
            for (int i = 0; i < prepared.args().size(); i++) {
                boundSql.setAdditionalParameter("p" + i, prepared.args().get(i));
            }
            return boundSql;
        }

        /** Map 直接用；单实体（如整实体注入）包成 item */
        private static Map<String, Object> toArgs(Object param) {
            if (param == null) {
                return Map.of();
            }
            if (param instanceof Map<?, ?> m) {
                Map<String, Object> out = new HashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
            return Map.of("item", param);
        }
    }
}
