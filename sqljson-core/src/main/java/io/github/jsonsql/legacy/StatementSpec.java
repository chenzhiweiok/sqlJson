package io.github.jsonsql.legacy;

import io.github.jsonsql.SqlSpecException;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** 一条语句的声明。字段与 README 里的标准格式一一对应。 */
@Deprecated
public final class StatementSpec {

    public final String id;
    public final String type;
    public final String table;
    public final String sql;
    public final List<String> columns;
    public final JsonNode where;
    public final JsonNode orderBy;
    public final Integer limit;
    public final Integer offset;
    public final boolean batch;
    public final String description;

    private StatementSpec(String id, String type, String table, String sql, List<String> columns,
                          JsonNode where, JsonNode orderBy, Integer limit, Integer offset,
                          boolean batch, String description) {
        this.id = id;
        this.type = type;
        this.table = table;
        this.sql = sql;
        this.columns = columns;
        this.where = where;
        this.orderBy = orderBy;
        this.limit = limit;
        this.offset = offset;
        this.batch = batch;
        this.description = description;
    }

    public static StatementSpec parse(String namespace, String name, JsonNode n) {
        String rawType = n.path("type").asText("select").toLowerCase();
        String type = "add".equals(rawType) ? "insert" : rawType;
        if (!List.of("select", "insert", "update", "delete", "count").contains(type)) {
            throw new SqlSpecException("不支持的 type: " + rawType + "（" + namespace + "." + name + "）");
        }

        List<String> columns = new ArrayList<>();
        JsonNode cols = n.get("columns");
        if (cols != null && cols.isArray()) {
            for (JsonNode c : cols) {
                columns.add(c.asText());
            }
        }

        Integer limit = n.has("limit") ? n.get("limit").asInt() : null;
        Integer offset = n.has("offset") ? n.get("offset").asInt() : null;

        String table = n.hasNonNull("table") ? n.get("table").asText() : null;
        String sql = n.hasNonNull("sql") ? n.get("sql").asText() : null;
        if (table == null && sql == null) {
            throw new SqlSpecException("table 与 sql 至少要有一个: " + namespace + "." + name);
        }

        return new StatementSpec(
                namespace + "." + name,
                type,
                table,
                sql,
                List.copyOf(columns),
                n.get("where"),
                n.get("orderBy"),
                limit,
                offset,
                n.path("batch").asBoolean(false),
                n.path("description").asText(""));
    }

    @Override
    public String toString() {
        return "StatementSpec{" + id + ", type=" + type + ", table=" + table + "}";
    }
}
