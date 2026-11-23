package io.github.jsonsql.mybatis;

import io.github.jsonsql.engine.SqlV2;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MyBatis 桥接：JSON 声明 → MappedStatement")
class SqlJsonMyBatisTest {

    private static final String JSON = """
            {
              "user": {
                "findPage": {
                  "sql": [
                    "SELECT id, user_name, age",
                    "  FROM t_user",
                    " WHERE age >= #1        -- [1] int minAge 最小年龄",
                    " ORDER BY id ASC"
                  ],
                  "params": [{ "index": 1, "name": "minAge", "type": "int" }]
                },
                "findById": {
                  "sql": "SELECT id, user_name, age FROM t_user WHERE id = #1   -- [1] long id"
                },
                "insert": {
                  "sql": [
                    "INSERT INTO t_user",
                    "       (#{item})",
                    "VALUES (#{item})"
                  ]
                },
                "countAdult": {
                  "sql": "SELECT COUNT(*) AS cnt FROM t_user WHERE age >= 18"
                }
              }
            }
            """;

    private static SqlSessionFactory factory;

    public static class User {
        public Long id;
        public String userName;
        public Integer age;

        @Override
        public String toString() {
            return "User{" + id + ", " + userName + ", " + age + "}";
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        DataSource ds = dataSource();

        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);

        SqlV2 engine = SqlV2.parse(JSON);
        int n = SqlJsonMyBatis.register(cfg, engine, "user", User.class, Map.of("countAdult", Map.class));
        assertEquals(4, n, "四条语句都应注册");

        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    private static DataSource dataSource() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mybatis_bridge;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("drop table if exists t_user");
            st.execute("""
                    create table t_user (
                      id bigint primary key auto_increment,
                      user_name varchar(64),
                      age int
                    )
                    """);
            st.execute("insert into t_user (user_name, age) values ('邱老师', 32), ('张三', 28), ('小明', 15)");
        }
        return ds;
    }

    @org.junit.jupiter.api.BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("delete from t_user");
            st.execute("insert into t_user (user_name, age) values ('邱老师', 32), ('张三', 28), ('小明', 15)");
        }
    }

    @Test
    @DisplayName("select 走 MyBatis：参数按 p0..pN 绑定")
    void selectThroughMyBatis() {
        try (SqlSession session = factory.openSession()) {
            List<User> list = session.selectList("user.findPage", Map.of("minAge", 18));
            assertEquals(2, list.size(), "只应查出 18 岁以上的两条");
            assertEquals("邱老师", list.get(0).userName);
            assertEquals(32, list.get(0).age);

            List<User> all = session.selectList("user.findPage", Map.of("minAge", 0));
            assertEquals(3, all.size());
        }
    }

    @Test
    @DisplayName("聚合查询返回单值")
    void countQuery() {
        try (SqlSession session = factory.openSession()) {
            Map<String, Object> row = session.selectOne("user.countAdult");
            // H2 返回的列标签是大写 CNT，取 key 时忽略大小写
            Object v = row.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("cnt"))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            assertEquals(2L, ((Number) v).longValue());
        }
    }

    @Test
    @DisplayName("insert 的整实体注入同样可用")
    void insertWithItem() {
        try (SqlSession session = factory.openSession(true)) {
            User u = new User();
            u.userName = "王五";
            u.age = 41;
            int rows = session.insert("user.insert", u);
            assertEquals(1, rows);

            List<User> found = session.selectList("user.findPage", Map.of("minAge", 40));
            assertTrue(found.stream().anyMatch(x -> "王五".equals(x.userName)));
        }
    }

    @Test
    @DisplayName("语句类型从 SQL 首词推导")
    void commandType() {
        assertEquals(org.apache.ibatis.mapping.SqlCommandType.SELECT, SqlJsonMyBatis.commandType("select 1"));
        assertEquals(org.apache.ibatis.mapping.SqlCommandType.SELECT, SqlJsonMyBatis.commandType("  WITH t as (select 1) select * from t"));
        assertEquals(org.apache.ibatis.mapping.SqlCommandType.INSERT, SqlJsonMyBatis.commandType("INSERT INTO a values (1)"));
        assertEquals(org.apache.ibatis.mapping.SqlCommandType.UPDATE, SqlJsonMyBatis.commandType("update a set b = 1"));
        assertEquals(org.apache.ibatis.mapping.SqlCommandType.DELETE, SqlJsonMyBatis.commandType("delete from a"));
    }

    @Test
    @DisplayName("注册后可以在 Configuration 里查到语句")
    void statementsRegistered() {
        Configuration cfg = factory.getConfiguration();
        assertNotNull(cfg.getMappedStatement("user.findPage"));
        assertNotNull(cfg.getMappedStatement("user.findById"));
        assertNotNull(cfg.getMappedStatement("user.insert"));
        assertNotNull(cfg.getMappedStatement("user.countAdult"));
    }
}
