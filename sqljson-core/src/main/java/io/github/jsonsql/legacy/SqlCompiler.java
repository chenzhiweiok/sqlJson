package io.github.jsonsql.legacy;

import io.github.jsonsql.SqlSpecException;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把声明式 JSON 编译成参数化 SQL。
 *
 * <p>两条编译路径：
 * <ol>
 *   <li>有 {@code sql} 字段：以模板为主，结构字段（where/orderBy/limit）追加到末尾；
 *       模板里出现 {@code where 1=1} 锚点时，条件以 {@code and} 接上。</li>
 *   <li>无 {@code sql} 字段：完全由 type + table + columns + where 生成。</li>
 * </ol>
 */
@Deprecated
public final class SqlCompiler {

    public record Prepared(String sql, List<Object> args) {
        @Override
        public String toString() {
            return sql + "  -- args=" + args;
        }
    }

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public Prepared compile(StatementSpec spec, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;

        if (spec.sql != null) {
            List<Object> args = new ArrayList<>();
            String sql = renderTemplate(spec.sql, spec, p, args);
            sql = appendStructures(spec, p, sql, args);
            return new Prepared(normalize(sql), args);
        }
        return buildFromStructure(spec, p);
    }

    // ---------------- 模板渲染 ----------------

    private String renderTemplate(String tpl, StatementSpec spec, Map<String, Object> p, List<Object> args) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < tpl.length()) {
            char ch = tpl.charAt(i);
            boolean isValue = ch == '#' && i + 1 < tpl.length() && tpl.charAt(i + 1) == '{';
            boolean isIdent = ch == '$' && i + 1 < tpl.length() && tpl.charAt(i + 1) == '{';
            if (isValue || isIdent) {
                int close = tpl.indexOf('}', i + 2);
                if (close < 0) {
                    throw new SqlSpecException("占位符没有闭合: " + spec.id);
                }
                String expr = tpl.substring(i + 2, close).trim();
                if (isIdent) {
                    out.append(ident(expr, spec));
                } else {
                    out.append(valueOf(expr, spec, p, args));
                }
                i = close + 1;
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private String valueOf(String expr, StatementSpec spec, Map<String, Object> p, List<Object> args) {
        // 宏：整个实体
        if ("item.columns".equals(expr)) {
            List<String> cols = itemColumns(spec, item(p));
            return String.join(", ", cols);
        }
        if ("item.values".equals(expr)) {
            List<String> cols = itemColumns(spec, item(p));
            cols.forEach(c -> args.add(prop(item(p), fieldOf(c, spec))));
            return placeholders(cols.size());
        }
        if ("item.assignments".equals(expr)) {
            List<String> cols = itemColumns(spec, item(p));
            StringBuilder sb = new StringBuilder();
            for (String c : cols) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(c).append(" = ?");
                args.add(prop(item(p), fieldOf(c, spec)));
            }
            return sb.toString();
        }
        if ("items.columns".equals(expr)) {
            List<String> cols = itemColumns(spec, firstOfItems(p));
            return String.join(", ", cols);
        }
        if ("items.tuples".equals(expr)) {
            Collection<?> items = items(p);
            List<String> cols = itemColumns(spec, items.iterator().next());
            StringBuilder sb = new StringBuilder();
            for (Object it : items) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(placeholders(cols.size())).append(')');
                for (String c : cols) {
                    args.add(prop(it, fieldOf(c, spec)));
                }
            }
            return sb.toString();
        }

        // item.xxx：实体属性
        if (expr.startsWith("item.")) {
            String field = expr.substring(5);
            Object v = prop(item(p), resolveField(field, spec));
            args.add(v);
            return "?";
        }
        if (expr.startsWith("items.")) {
            throw new SqlSpecException("集合宏只支持 items.columns / items.tuples，遇到: " + expr);
        }

        // 普通参数，支持 a.b 取嵌套
        Object v = lookup(p, expr);
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                return "null";
            }
            StringBuilder sb = new StringBuilder();
            for (Object o : c) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append('?');
                args.add(o);
            }
            return sb.toString();
        }
        args.add(v);
        return "?";
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    // ---------------- 结构追加 ----------------

    private String appendStructures(StatementSpec spec, Map<String, Object> p, String sql, List<Object> args) {
        StringBuilder sb = new StringBuilder(sql);
        String cond = condition(whereNode(spec, p), args);

        if (!cond.isEmpty()) {
            boolean hasWhere = sb.toString().toLowerCase().contains(" where ");
            if (hasWhere) {
                sb.append(" and (").append(cond).append(')');
            } else {
                sb.append(" where ").append(cond);
            }
        }

        String order = orderBySql(spec, p);
        if (!order.isEmpty()) {
            sb.append(" order by ").append(order);
        }

        Integer limit = limitOf(spec, p);
        if (limit != null) {
            sb.append(" limit ").append(limit);
            Integer offset = offsetOf(spec, p);
            if (offset != null && offset > 0) {
                sb.append(" offset ").append(offset);
            }
        }
        return sb.toString();
    }

    // ---------------- 结构生成 ----------------

    private Prepared buildFromStructure(StatementSpec spec, Map<String, Object> p) {
        List<Object> args = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        switch (spec.type) {
            case "select" -> {
                sb.append("select ");
                sb.append(spec.columns.isEmpty() ? "*" : String.join(", ", spec.columns));
                sb.append(" from ").append(ident(spec.table, spec));
            }
            case "count" -> sb.append("select count(*) from ").append(ident(spec.table, spec));
            case "delete" -> sb.append("delete from ").append(ident(spec.table, spec));
            case "insert" -> {
                if (spec.batch) {
                    Collection<?> items = items(p);
                    List<String> cols = itemColumns(spec, items.iterator().next());
                    sb.append("insert into ").append(ident(spec.table, spec))
                            .append(" (").append(String.join(", ", cols)).append(") values ");
                    int idx = 0;
                    for (Object it : items) {
                        if (idx++ > 0) {
                            sb.append(", ");
                        }
                        sb.append('(').append(placeholders(cols.size())).append(')');
                        for (String c : cols) {
                            args.add(prop(it, fieldOf(c, spec)));
                        }
                    }
                } else {
                    Object it = item(p);
                    List<String> cols = itemColumns(spec, it);
                    sb.append("insert into ").append(ident(spec.table, spec))
                            .append(" (").append(String.join(", ", cols)).append(") values (")
                            .append(placeholders(cols.size())).append(')');
                    for (String c : cols) {
                        args.add(prop(it, fieldOf(c, spec)));
                    }
                }
            }
            case "update" -> {
                Object it = item(p);
                List<String> cols = itemColumns(spec, it);
                sb.append("update ").append(ident(spec.table, spec)).append(" set ");
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(cols.get(i)).append(" = ?");
                    args.add(prop(it, fieldOf(cols.get(i), spec)));
                }
            }
            default -> throw new SqlSpecException("结构模式不支持的类型: " + spec.type);
        }

        String cond = condition(whereNode(spec, p), args);
        if (!cond.isEmpty()) {
            sb.append(" where ").append(cond);
        } else if (!"select".equals(spec.type) && !"count".equals(spec.type) && !"insert".equals(spec.type)) {
            throw new SqlSpecException("拒绝执行没有条件的 " + spec.type + ": " + spec.id);
        }

        if ("select".equals(spec.type)) {
            String order = orderBySql(spec, p);
            if (!order.isEmpty()) {
                sb.append(" order by ").append(order);
            }
            Integer limit = limitOf(spec, p);
            if (limit != null) {
                sb.append(" limit ").append(limit);
                Integer offset = offsetOf(spec, p);
                if (offset != null && offset > 0) {
                    sb.append(" offset ").append(offset);
                }
            }
        }
        return new Prepared(normalize(sb.toString()), args);
    }

    // ---------------- 条件（where JSON → SQL） ----------------

    private String condition(JsonNode where, List<Object> args) {
        if (where == null || where.isNull() || where.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = where.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            String lower = key.toLowerCase();
            if ("$or".equals(lower) || "$and".equals(lower)) {
                JsonNode arr = e.getValue();
                if (!arr.isArray()) {
                    throw new SqlSpecException(key + " 需要一个数组");
                }
                List<String> sub = new ArrayList<>();
                for (JsonNode n : arr) {
                    String s = condition(n, args);
                    if (!s.isEmpty()) {
                        sub.add("(" + s + ")");
                    }
                }
                if (!sub.isEmpty()) {
                    parts.add("(" + String.join("$or".equals(lower) ? " or " : " and ", sub) + ")");
                }
                continue;
            }
            if (key.startsWith("$")) {
                throw new SqlSpecException("未知的顶层操作符: " + key);
            }
            parts.add(fieldCondition(ident(key, null), e.getValue(), args));
        }
        return String.join(" and ", parts);
    }

    private String fieldCondition(String column, JsonNode v, List<Object> args) {
        if (v == null || v.isNull()) {
            return column + " is null";
        }
        if (v.isArray()) {
            if (v.isEmpty()) {
                return "1=0";
            }
            List<String> marks = new ArrayList<>();
            for (JsonNode n : v) {
                marks.add("?");
                args.add(toJava(n));
            }
            return column + " in (" + String.join(", ", marks) + ")";
        }
        if (v.isObject()) {
            List<String> parts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> op = it.next();
                parts.add(opCondition(column, op.getKey(), op.getValue(), args));
            }
            if (parts.isEmpty()) {
                throw new SqlSpecException("空的条件对象: " + column);
            }
            return String.join(" and ", parts);
        }
        args.add(toJava(v));
        return column + " = ?";
    }

    private String opCondition(String column, String rawOp, JsonNode v, List<Object> args) {
        String op = rawOp.toLowerCase();
        switch (op) {
            case "$eq" -> {
                if (v.isNull()) {
                    return column + " is null";
                }
                args.add(toJava(v));
                return column + " = ?";
            }
            case "$ne" -> {
                if (v.isNull()) {
                    return column + " is not null";
                }
                args.add(toJava(v));
                return column + " <> ?";
            }
            case "$gt" -> {
                args.add(toJava(v));
                return column + " > ?";
            }
            case "$gte" -> {
                args.add(toJava(v));
                return column + " >= ?";
            }
            case "$lt" -> {
                args.add(toJava(v));
                return column + " < ?";
            }
            case "$lte" -> {
                args.add(toJava(v));
                return column + " <= ?";
            }
            case "$like" -> {
                args.add(v.asText());
                return column + " like ?";
            }
            case "$notlike" -> {
                args.add(v.asText());
                return column + " not like ?";
            }
            case "$in", "$nin", "$notin" -> {
                if (!v.isArray()) {
                    throw new SqlSpecException(op + " 需要数组");
                }
                if (v.isEmpty()) {
                    return "$nin".equals(op) || "$notin".equals(op) ? "1=1" : "1=0";
                }
                List<String> marks = new ArrayList<>();
                for (JsonNode n : v) {
                    marks.add("?");
                    args.add(toJava(n));
                }
                String keyword = "$in".equals(op) ? " in (" : " not in (";
                return column + keyword + String.join(", ", marks) + ")";
            }
            case "$between" -> {
                if (!v.isArray() || v.size() != 2) {
                    throw new SqlSpecException("$between 需要两个元素");
                }
                args.add(toJava(v.get(0)));
                args.add(toJava(v.get(1)));
                return column + " between ? and ?";
            }
            case "$isnull" -> {
                return column + " is null";
            }
            case "$notnull" -> {
                return column + " is not null";
            }
            default -> throw new SqlSpecException("不支持的条件操作符: " + rawOp);
        }
    }

    // ---------------- 结构字段解析 ----------------

    private JsonNode whereNode(StatementSpec spec, Map<String, Object> p) {
        Object override = p.get("where");
        if (override instanceof JsonNode node) {
            return node;
        }
        if (override instanceof Map<?, ?> map) {
            return JsonNodeFactoryHolder.toNode(map);
        }
        return spec.where;
    }

    private String orderBySql(StatementSpec spec, Map<String, Object> p) {
        Object override = p.get("orderBy");
        if (override instanceof String s && !s.isBlank()) {
            return s;
        }
        JsonNode ob = spec.orderBy;
        if (ob == null || ob.isNull()) {
            return "";
        }
        if (ob.isTextual()) {
            return ob.asText();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode item : ob) {
            if (item.isTextual()) {
                parts.add(item.asText());
                continue;
            }
            String col = ident(item.path("field").asText(), spec);
            String dir = item.path("dir").asText("asc").toLowerCase();
            if (!"asc".equals(dir) && !"desc".equals(dir)) {
                throw new SqlSpecException("非法排序方向: " + dir);
            }
            parts.add(col + " " + dir);
        }
        return String.join(", ", parts);
    }

    private Integer limitOf(StatementSpec spec, Map<String, Object> p) {
        Object v = p.get("limit");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return spec.limit;
    }

    private Integer offsetOf(StatementSpec spec, Map<String, Object> p) {
        Object v = p.get("offset");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return spec.offset;
    }

    // ---------------- 工具 ----------------

    private String ident(String value, StatementSpec spec) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new SqlSpecException("非法标识符: " + value + (spec == null ? "" : "（" + spec.id + "）"));
        }
        return value;
    }

    private static Object item(Map<String, Object> p) {
        Object it = p.get("item");
        if (it == null) {
            throw new SqlSpecException("缺少 item 参数");
        }
        return it;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> items(Map<String, Object> p) {
        Object v = p.get("items");
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                throw new SqlSpecException("items 为空");
            }
            return (Collection<Object>) c;
        }
        throw new SqlSpecException("缺少 items 参数（应为集合）");
    }

    private static Object firstOfItems(Map<String, Object> p) {
        return items(p).iterator().next();
    }

    /** 参与本次语句的列：spec.columns 里"有值"的那些 */
    private static List<String> itemColumns(StatementSpec spec, Object bean) {
        List<String> out = new ArrayList<>();
        if (spec.columns.isEmpty()) {
            if (bean instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) {
                    if (m.get(k) != null) {
                        out.add(String.valueOf(k));
                    }
                }
            }
            return out;
        }
        for (String c : spec.columns) {
            if (prop(bean, fieldOf(c, spec)) != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** 列名与实体属性名一致时直接用；不一致时可用 spec 里的 columns 同名映射 */
    private static String fieldOf(String column, StatementSpec spec) {
        return column;
    }

    private static String resolveField(String expr, StatementSpec spec) {
        return expr;
    }

    static Object prop(Object bean, String name) {
        if (bean == null) {
            return null;
        }
        if (bean instanceof Map<?, ?> m) {
            return m.get(name);
        }
        Class<?> c = bean.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(bean);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static Object lookup(Map<String, Object> p, String expr) {
        String[] path = expr.split("\\.");
        Object cur = p;
        for (String seg : path) {
            cur = prop(cur, seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    static Object toJava(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isIntegralNumber()) {
            return v.canConvertToInt() ? (Object) v.asInt() : (Object) v.asLong();
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        return v.asText();
    }

    /** 折叠空白，单引号内不动 */
    static String normalize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean quoted = false;
        boolean lastSpace = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                out.append(ch);
                lastSpace = false;
                continue;
            }
            if (!quoted && Character.isWhitespace(ch)) {
                if (!lastSpace) {
                    out.append(' ');
                    lastSpace = true;
                }
            } else {
                out.append(ch);
                lastSpace = false;
            }
        }
        return out.toString().trim();
    }

    private static final class JsonNodeFactoryHolder {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static JsonNode toNode(Object o) {
            return MAPPER.valueToTree(o);
        }
    }
}
