package io.github.jsonsql.legacy;

import io.github.jsonsql.SqlSpecException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语句仓库：一个 JSON 文件 = 一个或多个命名空间，命名空间下每个 key 是一条语句。
 *
 * <pre>
 * {
 *   "user": {
 *     "insert": { "type": "insert", "sql": "..." },
 *     "find":   { "type": "select", "where": { ... } }
 *   }
 * }
 * </pre>
 *
 * 语句 id = 命名空间 + "." + key，例如 {@code user.insert}。
 */
@Deprecated
public final class JsonSqlStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, StatementSpec> specs = new LinkedHashMap<>();
    private final SqlCompiler compiler = new SqlCompiler();

    private JsonSqlStore() {
    }

    public static JsonSqlStore load(String resourcePath) {
        try (InputStream in = JsonSqlStore.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SqlSpecException("找不到资源: " + resourcePath);
            }
            return ofJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new SqlSpecException("读取失败: " + resourcePath);
        }
    }

    public static JsonSqlStore ofJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonSqlStore store = new JsonSqlStore();
            root.fields().forEachRemaining(ns -> {
                String namespace = ns.getKey();
                ns.getValue().fields().forEachRemaining(st -> {
                    StatementSpec spec = StatementSpec.parse(namespace, st.getKey(), st.getValue());
                    store.specs.put(spec.id, spec);
                });
            });
            return store;
        } catch (java.io.IOException e) {
            throw new SqlSpecException("JSON 解析失败: " + e.getMessage());
        }
    }

    public Set<String> ids() {
        return specs.keySet();
    }

    public StatementSpec spec(String id) {
        StatementSpec s = specs.get(id);
        if (s == null) {
            throw new SqlSpecException("未定义的语句: " + id + "，可用: " + specs.keySet());
        }
        return s;
    }

    public SqlCompiler.Prepared prepare(String id, Map<String, Object> params) {
        return compiler.compile(spec(id), params);
    }

    /** 只编译不执行，用于看最终 SQL */
    public String explain(String id, Map<String, Object> params) {
        return prepare(id, params).toString();
    }

    /** 检查所有语句是否可编译（空参数下能过一遍结构校验） */
    public List<String> lint() {
        List<String> problems = new java.util.ArrayList<>();
        for (StatementSpec s : specs.values()) {
            if (s.sql == null) {
                if (s.table == null) {
                    problems.add(s.id + ": 结构模式必须有 table");
                }
                if (("update".equals(s.type) || "delete".equals(s.type)) && s.where == null) {
                    problems.add(s.id + ": " + s.type + " 缺少 where，可能全表操作");
                }
            }
            if (s.sql != null && s.sql.isBlank()) {
                problems.add(s.id + ": sql 为空");
            }
        }
        return problems;
    }
}
