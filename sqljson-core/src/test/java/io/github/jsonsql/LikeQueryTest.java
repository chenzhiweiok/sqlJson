package io.github.jsonsql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.jsonsql.engine.SqlV2;

@DisplayName("模糊查询：转义、模式与多条件组合")
class LikeQueryTest {

    @Nested
    @DisplayName("Like 值对象")
    class Value {

        @Test
        @DisplayName("三种模式包装位置不同")
        void modes() {
            assertEquals("%张%", Like.contains("张").toPattern());
            assertEquals("张%", Like.prefix("张").toPattern());
            assertEquals("%张", Like.suffix("张").toPattern());
        }

        @Test
        @DisplayName("通配符被转义，只按字面匹配")
        void escapeWildcards() {
            assertEquals("%50\\%%", Like.contains("50%").toPattern());
            assertEquals("%a\\_b%", Like.contains("a_b").toPattern());
            assertEquals("%a\\\\b%", Like.contains("a\\b").toPattern());
        }

        @Test
        @DisplayName("反斜杠先转义，不会把新加的反斜杠再转一次")
        void escapeOrder() {
            // 输入 "%" 只应得到一个 \%，而不是 \\%
            assertEquals("%\\%%", Like.contains("%").toPattern());
        }

        @Test
        @DisplayName("raw 原样返回，用于已拼好的模式串")
        void raw() {
            assertEquals("%张%", Like.raw("%张%").toPattern());
            assertEquals("张_", Like.raw("张_").toPattern());
        }

        @Test
        @DisplayName("值不能为 null，避免和「不加条件」混淆")
        void nullRejected() {
            assertThrows(IllegalArgumentException.class, () -> Like.contains(null));
        }
    }

    @Nested
    @DisplayName("引擎渲染")
    class Render {

        private static final String JSON = """
                {
                  "t": {
                    "search": {
                      "sql": [
                        "SELECT id, user_name FROM t_like",
                        " WHERE 1=1",
                        "   AND (#1 IS NULL OR user_name LIKE #1)   -- [1] like keyword 姓名模糊",
                        "   AND (#2 IS NULL OR dept = #2)           -- [2] string dept 部门",
                        " ORDER BY id"
                      ]
                    }
                  }
                }
                """;

        @Test
        @DisplayName("type=like 时值被转义并包上 %")
        void wrapsValue() {
            SqlV2 engine = SqlV2.parse(JSON);
            SqlV2.Prepared p = engine.prepare("t.search", Map.of("keyword", "50%"));

            assertTrue(p.sql().contains("user_name LIKE ?"), p.sql());
            // ? IS NULL 处的参数是 null 语义上的原值，LIKE 处是模式串
            assertTrue(p.args().contains("%50\\%%"), "参数里应有转义后的模式串: " + p.args());
        }

        @Test
        @DisplayName("不传关键字时条件不生效")
        void nullMeansNoCondition() {
            SqlV2 engine = SqlV2.parse(JSON);
            SqlV2.Prepared p = engine.prepare("t.search", Map.of("dept", "技术部"));
            assertTrue(p.args().contains(null), "keyword 位置应绑定 null: " + p.args());
        }

        @Test
        @DisplayName("空串按 null 处理，不退化成 LIKE '%%'")
        void blankBecomesNull() {
            SqlV2 engine = SqlV2.parse(JSON);
            SqlV2.Prepared p = engine.prepare("t.search", Map.of("keyword", ""));
            assertTrue(p.args().contains(null), "空串应绑定 null: " + p.args());
            assertTrue(p.args().stream().noneMatch(a -> "%%".equals(a)));
        }

        @Test
        @DisplayName("直接传 Like 对象也能生效，不必在 JSON 里写 type")
        void likeObjectWins() {
            String json = """
                    {"t": {"search": {"sql": "SELECT id FROM t_like WHERE user_name LIKE #{kw}"}}}
                    """;
            SqlV2 engine = SqlV2.parse(json);
            assertEquals(List.of("张%"), engine.prepare("t.search", Map.of("kw", Like.prefix("张"))).args());
            assertEquals(List.of("张%"), engine.prepare("t.search", Map.of("kw", Like.raw("张%"))).args());
        }
    }

    @Nested
    @DisplayName("真实查询：多条件 + 通配符按字面匹配")
    class Integration {

        private static SqlJsonRepository<HomeEntity> repo;

        @BeforeAll
        static void setUp() throws Exception {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:like_test;DB_CLOSE_DELAY=-1");
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("drop table if exists t_like");
                st.execute("create table t_like (id bigint primary key, user_name varchar(64), dept varchar(32))");
                st.execute("insert into t_like values "
                        + "(1, '张三丰', '技术部'), "
                        + "(2, '张三', '技术部'), "
                        + "(3, '张%', '技术部'), "
                        + "(4, '王五', '产品部'), "
                        + "(5, '50%折扣', '运营部'), "
                        + "(6, 'a_b', '运营部')");
            }
            repo = SqlJsonRepository.of(HomeEntity.class, ds);
        }

        private static List<String> names(Map<String, Object> args) {
            return repo.query("search", args).stream().map(x -> x.userName).toList();
        }

        @Test
        @DisplayName("只传关键字：走包含匹配")
        void onlyKeyword() {
            assertEquals(List.of("张三丰", "张三", "张%"), names(Map.of("keyword", "张")));
        }

        @Test
        @DisplayName("关键字 + 部门：两个条件 AND")
        void keywordAndDept() {
            assertEquals(List.of("张三丰", "张三", "张%"), names(Map.of("keyword", "张", "dept", "技术部")));
            assertEquals(List.of(), names(Map.of("keyword", "张", "dept", "产品部")));
        }

        @Test
        @DisplayName("只传部门：关键字条件自动忽略")
        void onlyDept() {
            assertEquals(List.of("王五"), names(Map.of("dept", "产品部")));
        }

        @Test
        @DisplayName("都不传：返回全部，没有多余条件")
        void noCondition() {
            assertEquals(6, names(Map.of()).size());
        }

        @Test
        @DisplayName("用户输入 % 时只匹配字面量，而不是通配")
        void percentIsLiteral() {
            // 若不转义，'%' 会匹配全部 6 条
            assertEquals(List.of("张%", "50%折扣"), names(Map.of("keyword", "%")));
        }

        @Test
        @DisplayName("用户输入 _ 时也只匹配字面量")
        void underscoreIsLiteral() {
            assertEquals(List.of("a_b"), names(Map.of("keyword", "a_b")));
        }

        @Test
        @DisplayName("空串等价于不传")
        void blankEqualsAbsent() {
            assertEquals(6, names(Map.of("keyword", "  ".trim())).size());
        }

        @Test
        @DisplayName("前缀模式可用索引，结果集是包含模式的子集")
        void prefixMode() {
            assertEquals(List.of("张三丰", "张三", "张%"),
                    repo.query("searchPrefix", Map.of("keyword", "张")).stream().map(x -> x.userName).toList());
            assertEquals(List.of(), repo.query("searchPrefix", Map.of("keyword", "三")).stream().map(x -> x.userName).toList());
        }
    }

    /** 测试用实体：SQL 全部来自 JSON 资源 */
    @SqlJson(value = "likeDemo", table = "t_like", resource = "sqls/like-demo.json")
    static class HomeEntity {
        @SqlColumn(id = true)
        public Long id;
        @SqlColumn("user_name")
        public String userName;
        public String dept;
    }

    @Test
    @DisplayName("未声明 like 的普通字符串保持旧行为：调用方自己拼 %")
    void legacyBehaviorUnchanged() {
        SqlV2 engine = SqlV2.parse("""
                {"t": {"q": {
                  "sql": "SELECT id FROM t_like WHERE user_name LIKE #{kw}",
                  "params": [{"name": "kw", "type": "string"}]
                }}}
                """);
        assertEquals(List.of("%张%"), engine.prepare("t.q", Map.of("kw", "%张%")).args(),
                "旧语句不做任何加工，避免升级后行为突变");
    }
}
