package io.github.jsonsql;

import io.github.jsonsql.legacy.JsonSqlStore;
import io.github.jsonsql.legacy.SqlCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("v1 兼容层（legacy）")
class LegacyCompilerTest {

    private static final String JSON = """
            {
              "user": {
                "find": {
                  "type": "select",
                  "table": "t_user",
                  "sql": "select * from t_user where 1=1",
                  "where": {"status": "A"},
                  "orderBy": [{"field": "age", "dir": "desc"}],
                  "limit": 10
                },
                "removeAll": {
                  "type": "delete",
                  "table": "t_user"
                }
              }
            }
            """;

    @Test
    @DisplayName("where 1=1 锚点后追加条件，排序与分页也生效")
    void compilesWithAnchor() {
        JsonSqlStore store = JsonSqlStore.ofJson(JSON);
        SqlCompiler.Prepared p = store.prepare("user.find", Map.of());

        assertEquals("select * from t_user where 1=1 and (status = ?) order by age desc limit 10", p.sql());
        assertEquals(List.of("A"), p.args());
    }

    @Test
    @DisplayName("非法列名被白名单拦下")
    void rejectsIllegalColumn() {
        JsonSqlStore store = JsonSqlStore.ofJson(JSON);
        assertThrows(SqlSpecException.class,
                () -> store.prepare("user.find", Map.of("where", Map.of("1=1 or status", "A"))));
    }

    @Test
    @DisplayName("lint 能发现没有条件的删除")
    void lintsDangerousStatement() {
        JsonSqlStore store = JsonSqlStore.ofJson(JSON);
        List<String> problems = store.lint();
        assertTrue(problems.stream().anyMatch(s -> s.contains("removeAll")), "实际: " + problems);
    }
}
