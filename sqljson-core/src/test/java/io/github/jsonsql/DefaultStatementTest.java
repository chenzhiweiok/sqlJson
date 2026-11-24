package io.github.jsonsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.jsonsql.meta.statement.DefaultStatementSet;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.statement.FindByIdStatement;
import io.github.jsonsql.meta.statement.InsertStatement;
import io.github.jsonsql.meta.statement.StatementFactory;

@DisplayName("默认语句生成：策略拆分后每条可独立验证")
class DefaultStatementTest {

    @Nested
    @DisplayName("装配结果")
    class Assembly {

        @Test
        @DisplayName("七条语句全部生成，顺序固定")
        void sevenStatements() {
            ObjectNode ns = EntityDescriptor.of(TestEntities.User.class).defaultStatements();

            assertEquals(List.of("insert", "updateById", "deleteById",
                            "findById", "findByIds", "findAll", "findPage"),
                    DefaultStatementSet.names());
            assertEquals(7, ns.size());
            for (String name : DefaultStatementSet.names()) {
                assertTrue(ns.has(name), "缺少语句 " + name);
                assertTrue(ns.get(name).has("sql"), name + " 缺少 sql 数组");
            }
        }

        @Test
        @DisplayName("每条语句都带描述，便于 explain 与页面展示")
        void descriptions() {
            ObjectNode ns = EntityDescriptor.of(TestEntities.User.class).defaultStatements();
            for (String name : DefaultStatementSet.names()) {
                assertFalse(ns.get(name).get("description").asText().isBlank(), name + " 缺描述");
            }
        }

        @Test
        @DisplayName("重名直接抛异常，不静默覆盖")
        void duplicateRejected() {
            EntityDescriptor d = EntityDescriptor.of(TestEntities.User.class);
            StatementFactory dup = new InsertStatement();

            SqlSpecException e = assertThrows(SqlSpecException.class,
                    () -> DefaultStatementSet.build(d, List.of(new InsertStatement(), dup)));
            assertTrue(e.getMessage().contains("重复"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("列裁剪")
    class Columns {

        @Test
        @DisplayName("insert 排除自增主键与逻辑删除列")
        void insertColumns() {
            ObjectNode insert = EntityDescriptor.of(TestEntities.SoftUser.class)
                    .defaultStatements().get("insert").deepCopy();

            List<String> columns = texts(insert.get("columns"));
            assertFalse(columns.contains("id"), "自增主键不应出现在插入列里: " + columns);
            assertFalse(columns.contains("deleted"), "逻辑删除列不应被业务写入: " + columns);
            assertTrue(columns.contains("user_name"), "普通列应保留: " + columns);
            assertTrue(columns.contains("created_at"), "审计列应保留，由 fillAudit 填值: " + columns);
        }

        @Test
        @DisplayName("update 排除主键，保留可改列")
        void updateColumns() {
            ObjectNode update = EntityDescriptor.of(TestEntities.SoftUser.class)
                    .defaultStatements().get("updateById").deepCopy();

            List<String> columns = texts(update.get("columns"));
            assertFalse(columns.contains("id"), "主键进 WHERE，不进 SET");
            assertFalse(columns.contains("deleted"), "逻辑删除列由 restore 专门处理");
            assertTrue(columns.contains("user_name"));
        }

        @Test
        @DisplayName("非自增主键必须出现在 insert 列里")
        void manualIdIncluded() {
            ObjectNode insert = EntityDescriptor.of(ManualId.class)
                    .defaultStatements().get("insert").deepCopy();
            assertTrue(texts(insert.get("columns")).contains("code"),
                    "手工指定主键的实体，主键列必须可写");
        }
    }

    @Nested
    @DisplayName("SQL 形态")
    class Sql {

        @Test
        @DisplayName("按主键的语句都带参数注释，否则编译期会报没有定义 #1")
        void idParamDocumented() {
            ObjectNode ns = EntityDescriptor.of(TestEntities.User.class).defaultStatements();
            for (String name : List.of("updateById", "deleteById", "findById")) {
                String sql = String.join("\n", texts(ns.get(name).get("sql")));
                assertTrue(sql.contains("-- [1] long id"), name + " 缺少 #1 的参数注释: " + sql);
            }
        }

        @Test
        @DisplayName("findPage 带分页声明，且 SQL 里不写死 LIMIT")
        void findPage() {
            JsonNode page = EntityDescriptor.of(TestEntities.User.class)
                    .defaultStatements().get("findPage");

            assertEquals("page", page.get("pagination").get("style").asText());
            assertEquals("pageNo", page.get("pagination").get("pageParam").asText());
            assertEquals("pageSize", page.get("pagination").get("sizeParam").asText());

            String sql = String.join(" ", texts(page.get("sql"))).toUpperCase();
            assertFalse(sql.contains("LIMIT"), "分页子句应由引擎追加，不能写死在语句里: " + sql);
            assertTrue(sql.contains("ORDER BY ID"), "分页需要稳定排序: " + sql);
        }

        @Test
        @DisplayName("findAll 与 findPage 都用显式列清单，不用 *")
        void explicitColumns() {
            ObjectNode ns = EntityDescriptor.of(TestEntities.User.class).defaultStatements();
            for (String name : List.of("findById", "findAll", "findPage")) {
                String sql = String.join(" ", texts(ns.get(name).get("sql")));
                assertFalse(sql.contains("SELECT *"), name + " 不应使用 SELECT *: " + sql);
                assertTrue(sql.contains("user_name"), name + " 应列出具体列: " + sql);
            }
        }
    }

    @Nested
    @DisplayName("扩展")
    class Extension {

        @Test
        @DisplayName("传入自定义集合可以只生成想生成的语句")
        void customSet() {
            ObjectNode ns = DefaultStatementSet.build(
                    EntityDescriptor.of(TestEntities.User.class),
                    List.of(new FindByIdStatement()));
            assertEquals(1, ns.size());
            assertTrue(ns.has("findById"));
        }
    }

    /** 手工指定主键（非自增）的实体，用于验证主键列必须可写 */
    @SqlJson(value = "manual", table = "t_manual")
    static class ManualId {
        @SqlColumn(id = true)
        String code;
        String name;

        ManualId() {
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
