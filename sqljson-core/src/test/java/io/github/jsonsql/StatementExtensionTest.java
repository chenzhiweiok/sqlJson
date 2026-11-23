package io.github.jsonsql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.Statements;
import io.github.jsonsql.meta.statement.StatementFactory;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("模板扩展：实现 StatementFactory 即可增删默认语句")
class StatementExtensionTest {

    /** 扩展语句：统计总数，演示「加一条」 */
    public static class CountAllStatement implements StatementFactory {
        @Override
        public String name() {
            return "countAll";
        }

        @Override
        public ObjectNode build(EntityDescriptor entity) {
            ObjectNode node = Statements.node();
            node.set("sql", Statements.sql("SELECT COUNT(*) AS cnt FROM " + entity.table()));
            node.put("description", "扩展：统计总数");
            return node;
        }
    }

    /** 覆盖内置语句：只查 ACTIVE 的分页，演示「改一条」 */
    public static class ActiveOnlyPage implements StatementFactory {
        @Override
        public String name() {
            return "findPage";
        }

        @Override
        public ObjectNode build(EntityDescriptor entity) {
            ObjectNode node = Statements.node();
            node.set("sql", Statements.sql(
                    "SELECT " + Statements.selectList(entity),
                    "  FROM " + entity.table(),
                    " WHERE status = 'ACTIVE'",
                    " ORDER BY " + entity.idColumn() + " ASC"));
            ObjectNode page = node.putObject("pagination");
            page.put("style", "page");
            page.put("pageParam", "pageNo");
            page.put("sizeParam", "pageSize");
            node.put("description", "覆盖：只查 ACTIVE");
            return node;
        }
    }

    /** 没有无参构造，用来验证装配期就报错 */
    public static class BrokenStatement implements StatementFactory {
        public BrokenStatement(String unused) {
        }

        @Override
        public String name() {
            return "broken";
        }

        @Override
        public ObjectNode build(EntityDescriptor entity) {
            return Statements.node();
        }
    }

    public static class Row {
        public Long id;
        public String userName;
        public String status;
    }

    @BeforeAll
    static void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ext_test;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("drop table if exists t_ext");
            st.execute("create table t_ext (id bigint primary key auto_increment, user_name varchar(32), status varchar(16))");
            st.execute("insert into t_ext (user_name, status) values ('张三','ACTIVE'), ('李四','INACTIVE'), ('王五','ACTIVE')");
        }
    }

    @Test
    @DisplayName("注解上声明工厂，实体就多一条语句，并且能执行")
    void annotatedExtension() {
        SqlJsonRepository<WithCount> repo = SqlJsonRepository.of(WithCount.class, dataSource());
        assertTrue(repo.statements().contains("countAll"), "扩展语句应装配进来: " + repo.statements());

        List<Map<String, Object>> rows = repo.queryMaps("countAll", Map.of());
        assertEquals(3L, ((Number) rows.get(0).get("cnt")).longValue());
    }

    @Test
    @DisplayName("与内置同名时覆盖内容，且位置不变")
    void overrideBuiltIn() {
        EntityDescriptor d = EntityDescriptor.of(WithOverride.class);
        List<String> names = new java.util.ArrayList<>();
        d.defaultStatements().fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("insert", "updateById", "deleteById", "findById", "findAll", "findPage"), names,
                "覆盖不应改变语句顺序");

        String sql = d.defaultStatements().get("findPage").get("sql").get(0).asText();
        assertTrue(sql.contains("ACTIVE") || sql.contains("status"), "findPage 应被自定义内容替换: " + sql);
    }

    @Test
    @DisplayName("编程式传入扩展，与注解声明可以共存")
    void programmaticExtension() {
        SqlJsonRepository<WithCount> repo = SqlJsonRepository.of(
                WithCount.class, dataSource(), Dialect.mysql(), List.of(new ActiveOnlyPage()));
        assertTrue(repo.statements().contains("countAll"), "注解声明的仍在");
        assertTrue(repo.statements().contains("findPage"));
        // findPage 已被参数里的工厂覆盖，只查 ACTIVE
        assertEquals(2, repo.findPage(1, 10).total());
    }

    @Test
    @DisplayName("工厂没有无参构造时，装配期直接报错而不是查询时报错")
    void brokenFactory() {
        SqlSpecException e = assertThrows(SqlSpecException.class,
                () -> EntityDescriptor.of(WithBroken.class));
        assertTrue(e.getMessage().contains("无法实例化"), e.getMessage());
    }

    private static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ext_test;DB_CLOSE_DELAY=-1");
        return ds;
    }

    @SqlJson(value = "ext", table = "t_ext", statements = {CountAllStatement.class})
    static class WithCount {
        @SqlColumn(id = true, generated = true)
        Long id;
        @SqlColumn("user_name")
        String userName;
        String status;
    }

    @SqlJson(value = "ext2", table = "t_ext", statements = {ActiveOnlyPage.class})
    static class WithOverride {
        @SqlColumn(id = true, generated = true)
        Long id;
        @SqlColumn("user_name")
        String userName;
        String status;
    }

    @SqlJson(value = "ext3", table = "t_ext", statements = {BrokenStatement.class})
    static class WithBroken {
        @SqlColumn(id = true, generated = true)
        Long id;
    }
}
