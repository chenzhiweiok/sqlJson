package io.github.jsonsql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.jsonsql.engine.SqlV2;

@DisplayName("SqlV2 编译层")
class SqlV2Test {

    private static SqlV2 engine(String json) {
        return SqlV2.parse(json);
    }

    @Nested
    @DisplayName("参数描述")
    class ParamDescription {

        @Test
        @DisplayName("行内注释能解析出编号、类型、名称、说明")
        void parsesFromComments() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x WHERE a = #1    -- [1] string name 姓名"]}}}
                    """);
            List<SqlV2.Param> params = e.doc("t.s").params();
            assertEquals(1, params.size());
            assertEquals(1, params.get(0).index());
            assertEquals("string", params.get(0).type());
            assertEquals("name", params.get(0).name());
            assertEquals("姓名", params.get(0).desc());
        }

        @Test
        @DisplayName("params 数组合并进行内注释，只补差异字段")
        void mergesParamsArray() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x ORDER BY #1   -- [1] ident sort 排序"],
                               "params":[{"index":1,"enum":["age","id"]}]}}}
                    """);
            SqlV2.Param p = e.doc("t.s").params().get(0);
            assertEquals("ident", p.type());
            assertEquals("sort", p.name());
            assertEquals(List.of("age", "id"), p.enumValues());
        }

        @Test
        @DisplayName("注释被剥离，SQL 里不残留 --")
        void stripsComments() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT 1 FROM x WHERE a = #1  -- [1] string a 说明"]}}}
                    """);
            assertTrue(!e.doc("t.s").sql().contains("--"));
        }
    }

    @Nested
    @DisplayName("位置占位 #n")
    class Positional {

        @Test
        @DisplayName("按描述做类型转换：字符串 \"18\" -> int 18")
        void convertsByType() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x WHERE age = #1   -- [1] int age 年龄"]}}}
                    """);
            SqlV2.Prepared p = e.prepare("t.s", Map.of("age", "18"));
            assertEquals("SELECT * FROM x WHERE age = ?", p.sql());
            assertEquals(List.of(18), p.args());
        }

        @Test
        @DisplayName("缺参数且非必填时绑 null")
        void bindsNullWhenAbsent() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x WHERE a = #1   -- [1] string a 可选"]}}}
                    """);
            SqlV2.Prepared p = e.prepare("t.s", Map.of());
            assertEquals(List.of(), p.args().stream().filter(java.util.Objects::nonNull).toList());
            assertEquals("?", p.sql().substring(p.sql().indexOf('=') + 1).trim());
        }

        @Test
        @DisplayName("必填缺失直接报错")
        void requiredMissing() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x WHERE a = #1   -- [1] string a 必填"],
                               "params":[{"index":1,"required":true}]}}}
                    """);
            assertThrows(SqlSpecException.class, () -> e.prepare("t.s", Map.of()));
        }

        @Test
        @DisplayName("引用未定义的编号报错")
        void unknownIndex() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x WHERE a = #2   -- [1] string a 说明"]}}}
                    """);
            assertThrows(SqlSpecException.class, () -> e.prepare("t.s", Map.of("a", "1")));
        }
    }

    @Nested
    @DisplayName("标识符与 raw")
    class IdentAndRaw {

        @Test
        @DisplayName("ident 原样拼接，不占参数位")
        void identInlines() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x ORDER BY #1  -- [1] ident sort 排序"],
                               "params":[{"index":1,"enum":["age"]}]}}}
                    """);
            SqlV2.Prepared p = e.prepare("t.s", Map.of("sort", "age"));
            assertEquals("SELECT * FROM x ORDER BY age", p.sql());
            assertTrue(p.args().isEmpty());
        }

        @Test
        @DisplayName("ident 注入特征字符被拦下")
        void identRejectsInjection() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x ORDER BY #1  -- [1] ident sort 排序"]}}}
                    """);
            SqlSpecException ex = assertThrows(SqlSpecException.class,
                    () -> e.prepare("t.s", Map.of("sort", "id; drop table x")));
            assertTrue(ex.getMessage().contains("非法标识符"));
        }

        @Test
        @DisplayName("ident 不在 enum 白名单被拦下")
        void identEnum() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x ORDER BY #1  -- [1] ident sort 排序"],
                               "params":[{"index":1,"enum":["age","id"]}]}}}
                    """);
            SqlSpecException ex = assertThrows(SqlSpecException.class,
                    () -> e.prepare("t.s", Map.of("sort", "password")));
            assertTrue(ex.getMessage().contains("白名单"));
        }

        @Test
        @DisplayName("raw 只接受整数")
        void rawDigitsOnly() {
            SqlV2 e = engine("""
                    {"t":{"s":{"sql":["SELECT * FROM x LIMIT #1  -- [1] raw n 条数"]}}}
                    """);
            assertEquals("SELECT * FROM x LIMIT 10", e.prepare("t.s", Map.of("n", 10)).sql());
            assertThrows(SqlSpecException.class, () -> e.prepare("t.s", Map.of("n", "10; drop")));
        }
    }

    @Nested
    @DisplayName("整实体 #{item}")
    class ItemInjection {

        private static final String INSERT = """
                {"u":{"ins":{"columns":["id","user_name"],
                             "sql":["INSERT INTO t_user (#{item})","VALUES (#{item})"]}}}
                """;

        @Test
        @DisplayName("列区展开列名、值区展开占位符")
        void bothRegions() {
            SqlV2 e = engine(INSERT);
            SqlV2.Prepared p = e.prepare("u.ins", Map.of("item", Map.of("id", 1, "user_name", "邱")));
            assertEquals("INSERT INTO t_user (id, user_name) VALUES (?, ?)", p.sql());
            assertEquals(List.of(1, "邱"), p.args());
        }

        @Test
        @DisplayName("只注入有值的字段")
        void skipsNullFields() {
            SqlV2 e = engine(INSERT);
            SqlV2.Prepared p = e.prepare("u.ins", Map.of("item", Map.of("user_name", "邱")));
            assertEquals("INSERT INTO t_user (user_name) VALUES (?)", p.sql());
            assertEquals(List.of("邱"), p.args());
        }

        @Test
        @DisplayName("SET 区展开成赋值串")
        void setRegion() {
            SqlV2 e = engine("""
                    {"u":{"upd":{"columns":["user_name","age"],
                                 "sql":["UPDATE t_user SET #{item}"," WHERE id = #1  -- [1] long id 主键"]}}}
                    """);
            SqlV2.Prepared p = e.prepare("u.upd",
                    Map.of("item", Map.of("user_name", "x", "age", 20), "id", 5L));
            assertEquals("UPDATE t_user SET user_name = ?, age = ? WHERE id = ?", p.sql());
            assertEquals(List.of("x", 20, 5L), p.args());
        }

        @Test
        @DisplayName("出现在无法判断的位置直接报错")
        void illegalRegion() {
            SqlV2 e = engine("""
                    {"u":{"q":{"columns":["id"],"sql":["SELECT #{item} FROM t_user"]}}}
                    """);
            SqlSpecException ex = assertThrows(SqlSpecException.class,
                    () -> e.prepare("u.q", Map.of("item", Map.of("id", 1))));
            assertTrue(ex.getMessage().contains("无法判断"));
        }

        @Test
        @DisplayName("多实体 item1 / item2 各取各的字段")
        void multipleItems() {
            SqlV2 e = engine("""
                    {"u":{"c":{"sql":["INSERT INTO t_user (user_name, age) VALUES (#{item1.user_name}, #{item2.age})"]}}}
                    """);
            SqlV2.Prepared p = e.prepare("u.c", Map.of(
                    "item1", Map.of("user_name", "A"),
                    "item2", Map.of("age", 20)));
            assertEquals("INSERT INTO t_user (user_name, age) VALUES (?, ?)", p.sql());
            assertEquals(List.of("A", 20), p.args());
        }
    }

    @Nested
    @DisplayName("普通命名参数")
    class Named {

        @Test
        @DisplayName("集合展开成多个占位符")
        void expandsCollection() {
            SqlV2 e = engine("""
                    {"u":{"q":{"sql":["SELECT * FROM t_user WHERE id IN (#{ids})"]}}}
                    """);
            SqlV2.Prepared p = e.prepare("u.q", Map.of("ids", List.of(1, 2, 3)));
            assertEquals("SELECT * FROM t_user WHERE id IN (?, ?, ?)", p.sql());
            assertEquals(List.of(1, 2, 3), p.args());
        }

        @Test
        @DisplayName("空集合渲染成 null，不产生 in ()")
        void emptyCollection() {
            SqlV2 e = engine("""
                    {"u":{"q":{"sql":["SELECT * FROM t_user WHERE id IN (#{ids})"]}}}
                    """);
            assertEquals("SELECT * FROM t_user WHERE id IN (null)",
                    e.prepare("u.q", Map.of("ids", List.of())).sql());
        }
    }

    @Nested
    @DisplayName("模板")
    class Templates {

        private static final String TPL = """
                {
                  "templates": {
                    "base": {"sql": ["SELECT {{cols}}", "  FROM {{table}}", " WHERE 1=1"]}
                  },
                  "u": {
                    "find": {"template": "base", "vars": {"cols": "id, user_name", "table": "t_user"},
                             "sql": ["   AND status = #1   -- [1] string status 状态"]}
                  }
                }
                """;

        @Test
        @DisplayName("{{var}} 编译期替换，语句 sql 追加在后")
        void expandsVarsAndAppends() {
            SqlV2 e = engine(TPL);
            String sql = e.doc("u.find").sql();
            assertTrue(sql.startsWith("SELECT id, user_name"));
            assertTrue(sql.contains("FROM t_user"));
            assertTrue(sql.contains("AND status = #1"));
        }

        @Test
        @DisplayName("变量缺失在加载期就报错")
        void missingVar() {
            SqlSpecException ex = assertThrows(SqlSpecException.class, () -> engine("""
                    {
                      "templates": {"base": {"sql": ["SELECT {{cols}} FROM {{table}}"]}},
                      "u": {"find": {"template": "base", "vars": {"table": "t_user"}}}
                    }
                    """));
            assertTrue(ex.getMessage().contains("{{cols}}"));
        }

        @Test
        @DisplayName("引用了不存在的模板")
        void unknownTemplate() {
            assertThrows(SqlSpecException.class, () -> engine("""
                    {"u":{"find":{"template":"nope","sql":["SELECT 1"]}}}
                    """));
        }
    }

    @Nested
    @DisplayName("分页")
    class Pagination {

        private static final String OFFSET_STYLE = """
                {"u":{"p":{"sql":["SELECT id FROM t_user"],
                           "pagination":{"style":"offset","sizeParam":"pageSize","offsetParam":"offset"}}}}
                """;

        @Test
        @DisplayName("offset 风格：直接追加 LIMIT/OFFSET")
        void offsetStyle() {
            SqlV2.PagePrepared p = engine(OFFSET_STYLE)
                    .preparePage("u.p", Map.of("pageSize", 2, "offset", 4));
            assertEquals("SELECT id FROM t_user LIMIT 2 OFFSET 4", p.listSql());
            assertEquals(3, p.pageNo());
            assertEquals(2, p.pageSize());
        }

        @Test
        @DisplayName("page 风格：由 pageNo 算出 offset")
        void pageStyle() {
            SqlV2 e = engine("""
                    {"u":{"p":{"sql":["SELECT id FROM t_user"],
                               "pagination":{"style":"page","pageParam":"pageNo","sizeParam":"pageSize"}}}}
                    """);
            SqlV2.PagePrepared p = e.preparePage("u.p", Map.of("pageNo", 2, "pageSize", 3));
            assertEquals("SELECT id FROM t_user LIMIT 3 OFFSET 3", p.listSql());
            assertEquals(2, p.pageNo());
        }

        @Test
        @DisplayName("SQL 自带 LIMIT 时不再重复追加")
        void keepsExistingLimit() {
            SqlV2 e = engine("""
                    {"u":{"p":{"sql":["SELECT id FROM t_user LIMIT 5"],
                               "pagination":{"style":"offset"}}}}
                    """);
            assertEquals("SELECT id FROM t_user LIMIT 5",
                    e.preparePage("u.p", Map.of("pageSize", 10, "offset", 0)).listSql());
        }

        @Test
        @DisplayName("自动推导 count：换 select 列表、去掉 order by")
        void derivesCount() {
            SqlV2 e = engine("""
                    {"u":{"p":{"sql":["SELECT id, user_name FROM t_user"," WHERE 1=1"," ORDER BY id ASC"],
                               "pagination":{"style":"offset"}}}}
                    """);
            SqlV2.PagePrepared p = e.preparePage("u.p", Map.of("pageSize", 10));
            assertEquals("SELECT COUNT(*) FROM t_user WHERE 1=1", p.countSql());
        }

        @Test
        @DisplayName("含 GROUP BY 时拒绝自动推导")
        void rejectsGroupBy() {
            SqlV2 e = engine("""
                    {"u":{"p":{"sql":["SELECT status, COUNT(*) FROM t_user GROUP BY status"],
                               "pagination":{"style":"offset"}}}}
                    """);
            SqlSpecException ex = assertThrows(SqlSpecException.class,
                    () -> e.preparePage("u.p", Map.of("pageSize", 10)));
            assertTrue(ex.getMessage().contains("GROUP BY"));
        }

        @Test
        @DisplayName("未声明 pagination 时 preparePage 报错")
        void requiresPagination() {
            SqlV2 e = engine("""
                    {"u":{"p":{"sql":["SELECT id FROM t_user"]}}}
                    """);
            assertThrows(SqlSpecException.class, () -> e.preparePage("u.p", Map.of()));
        }
    }

    @Test
    @DisplayName("查不存在的语句：报错信息里带可用列表")
    void unknownStatement() {
        SqlV2 e = engine("""
                {"u":{"a":{"sql":["SELECT 1"]}}}
                """);
        SqlSpecException ex = assertThrows(SqlSpecException.class, () -> e.prepare("u.b", Map.of()));
        assertTrue(ex.getMessage().contains("u.a"));
    }
}
