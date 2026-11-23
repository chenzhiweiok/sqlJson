package io.github.jsonsql.engine;

import io.github.jsonsql.Like;
import io.github.jsonsql.SqlJsonRepository;
import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.meta.EntityDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2 编译引擎：把 JSON 语句渲染成「参数化 SQL + 参数列表」。
 *
 * <p>设计上只有一个约束：<b>SQL 是唯一主体</b>。占位符直接写在 SQL 里，类型与说明贴在旁边，
 * 打开 JSON 就能看到最终会执行什么。所有动态能力都收敛到占位符，而不是在 Java 里拼字符串。
 *
 * <pre>
 * 占位符                     含义
 * #1 #2 #3                  位置占位，对应 params[]（或行内注释 -- [1] type name desc）
 * #{item}                   整实体注入：列区展开列名，值区展开占位符，SET 区展开赋值
 * #{item1} #{item2}         多实体，各自注入
 * #{item.age}               实体属性
 * #{age}                    普通命名参数
 * {{var}}                   编译期模板变量（加载时替换，不是运行期）
 * </pre>
 *
 * <p><b>两种占位，安全责任完全不同</b>：
 * <ul>
 *   <li>值（{@code #n}、{@code #{...}}）一律渲染成 {@code ?}，交给 PreparedStatement 绑定，
 *       天然免疫注入；</li>
 *   <li>标识符与 raw 会拼接，必须通过 {@code params[]} 里的 {@code type: ident / raw} 显式声明，
 *       且 ident 还要过白名单正则。这是整套设计里<b>唯一的拼接出口</b>，
 *       审 SQL 安全性只需要审这一个字段。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：装配完成后实例只读（{@code docs} 不再变更），可以被多线程并发
 * {@link #prepare}。唯一可变的是 {@link #rewrite} 装的改写器，必须在装配阶段设置，
 * 运行期不要改——它不是 volatile，改了别的线程也未必看得见。
 *
 * <p><b>为什么不做 SQL 缓存</b>：渲染成本主要是几次字符串扫描，和一次 HashMap 查找同量级；
 * 而缓存键必须包含全部参数值，命中率低、内存占用高，收益为负。真正的开销在数据库往返，
 * 不在这一步。
 */
public final class SqlV2 {

    public record Param(int index, String name, String type, String desc,
                        List<String> enumValues, boolean required, Object defaultValue) {
    }

    /**
     * 一条语句的编译期定义。
     *
     * <p>保留的不只是 SQL：{@code params} 是参数描述（类型、白名单、说明），
     * {@code columns} 是整实体注入时允许的列清单，{@code pagination} 是分页声明。
     * 有了这些，才能在渲染前做校验、在渲染后做解释。
     *
     * @param id          语句 id，形如 {@code user.findPage}
     * @param sql         已剥离注释、合并成一行行文本的 SQL 原文
     * @param params      参数描述，按 index 升序
     * @param columns     允许写入的列清单，空表示运行时从实体推断
     * @param description 人读的说明，仅用于文档与日志
     * @param pagination  分页声明，null 表示不分页
     */
    public record Doc(String id, String sql, List<Param> params, List<String> columns,
                      String description, Pagination pagination) {
    }

    /** 分页声明。style=offset 用 offset/size；style=page 用 pageNo/size。 */
    public record Pagination(String style, String pageParam, String sizeParam,
                             String offsetParam, String countSql) {
    }

    /** 分页编译结果：一条取数据、一条取总数，参数已对齐。 */
    public record PagePrepared(String listSql, List<Object> listArgs,
                               String countSql, List<Object> countArgs,
                               int pageNo, int pageSize) {
    }

    /**
     * 渲染结果：一条带 {@code ?} 的 SQL 和与之逐一对应的参数。
     *
     * <p>两者由同一次深度优先遍历产出，顺序天然一致——不需要 {@code :1 :2} 这种编号，
     * 也就不会出现「参数和占位符错位」这类常见 bug。
     *
     * @param sql  参数化 SQL
     * @param args 参数值，{@code args.get(i)} 对应 SQL 里第 i 个 {@code ?}
     */
    public record Prepared(String sql, List<Object> args) {
        @Override
        public String toString() {
            return sql + "  -- args=" + args;
        }
    }

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DIGITS = Pattern.compile("-?\\d+");
    private static final Pattern COMMENT_PARAM =
            Pattern.compile("^\\s*\\[(\\d+)]\\s+(\\S+)\\s+(\\S+)(?:\\s+(.*))?$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Doc> docs = new LinkedHashMap<>();

    /** 编译后的统一改写钩子，例如逻辑删除过滤 */
    private java.util.function.UnaryOperator<String> rewriter = s -> s;

    private SqlV2() {
    }

    /** 装一个 SQL 改写器，所有经此引擎编译的语句都会经过它 */
    public SqlV2 rewrite(java.util.function.UnaryOperator<String> r) {
        this.rewriter = r == null ? s -> s : r;
        return this;
    }

    /**
     * 从 classpath 加载 JSON 并编译。
     *
     * @param resourcePath 相对 classpath 根目录的路径，例如 {@code sqls/user.json}
     * @return 编译好的引擎实例
     * @throws SqlSpecException 资源不存在、读取失败或 JSON 结构错误
     */
    public static SqlV2 load(String resourcePath) {
        try (InputStream in = SqlV2.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SqlSpecException("找不到资源: " + resourcePath);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new SqlSpecException("读取失败: " + resourcePath);
        }
    }

    /**
     * 从 JSON 字符串编译。
     *
     * <p>除了手工维护的文件，这个入口还用于「元数据由程序生成」的场景——
     * {@link SqlJsonRepository} 就是把注解反射成 JSON 后走这里，从而保证注解版与
     * 手写版共享同一套编译逻辑。
     *
     * <p>顶层允许一个可选的 {@code templates} 段，其余每个 key 是一个命名空间，
     * 其下每个 key 是一条语句。
     *
     * @param json JSON 文本
     * @return 编译好的引擎实例
     * @throws SqlSpecException JSON 无法解析或语句定义不完整
     */
    public static SqlV2 parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            SqlV2 engine = new SqlV2();

            // templates 段先收集，其余命名空间可以引用它
            Map<String, JsonNode> templates = new LinkedHashMap<>();
            JsonNode tplNode = root.get("templates");
            if (tplNode != null && tplNode.isObject()) {
                tplNode.fields().forEachRemaining(e -> templates.put(e.getKey(), e.getValue()));
            }

            root.fields().forEachRemaining(ns -> {
                if ("templates".equals(ns.getKey())) {
                    return;
                }
                ns.getValue().fields().forEachRemaining(st -> {
                    String id = ns.getKey() + "." + st.getKey();
                    engine.docs.put(id, buildDoc(id, st.getValue(), templates));
                });
            });
            return engine;
        } catch (java.io.IOException e) {
            throw new SqlSpecException("JSON 解析失败: " + e.getMessage());
        }
    }

    private static Doc buildDoc(String id, JsonNode n, Map<String, JsonNode> templates) {
        // ---------- 1. 模板展开（编译期变量 {{x}}） ----------
        Map<String, String> vars = new LinkedHashMap<>();
        JsonNode tpl = null;
        if (n.hasNonNull("template")) {
            String tplName = n.get("template").asText();
            tpl = templates.get(tplName);
            if (tpl == null) {
                throw new SqlSpecException(id + ": 找不到模板 " + tplName);
            }
            if (tpl.has("vars")) {
                tpl.get("vars").fields().forEachRemaining(e -> vars.put(e.getKey(), e.getValue().asText()));
            }
        }
        if (n.has("vars")) {
            n.get("vars").fields().forEachRemaining(e -> vars.put(e.getKey(), e.getValue().asText()));
        }

        StringBuilder sqlBuf = new StringBuilder();
        if (tpl != null) {
            sqlBuf.append(expandVars(id, sqlText(tpl, id), vars));
        }
        if (n.has("sql")) {
            if (sqlBuf.length() > 0) {
                sqlBuf.append('\n');
            }
            sqlBuf.append(expandVars(id, sqlText(n, id), vars));
        }
        String sql = sqlBuf.toString();
        if (sql.isBlank()) {
            throw new SqlSpecException(id + ": 缺少 sql");
        }

        Map<Integer, Param> byIndex = new LinkedHashMap<>();
        Map<String, Param> byName = new LinkedHashMap<>();

        // ---------- 2. 行内注释里的描述：-- [1] string status 用户状态 ----------
        String[] lines = sql.split("\n", -1);
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            int c = line.indexOf("--");
            String content = line;
            if (c >= 0) {
                content = line.substring(0, c);
                String comment = line.substring(c + 2);
                Matcher m = COMMENT_PARAM.matcher(comment);
                if (m.matches()) {
                    int idx = Integer.parseInt(m.group(1));
                    String type = m.group(2).toLowerCase();
                    String name = m.group(3);
                    String desc = m.group(4) == null ? "" : m.group(4).trim();
                    byIndex.putIfAbsent(idx, new Param(idx, name, type, desc, List.of(), false, null));
                }
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(content.stripTrailing());
        }
        sql = body.toString();

        // ---------- 3. params：先模板、后语句（语句覆盖） ----------
        if (tpl != null && tpl.has("params")) {
            applyParams(tpl.get("params"), byIndex, byName);
        }
        if (n.has("params")) {
            applyParams(n.get("params"), byIndex, byName);
        }
        byIndex.values().forEach(p -> byName.put(p.name(), p));

        // ---------- 4. columns / pagination / description ----------
        List<String> columns = readColumns(n.has("columns") ? n
                : (tpl != null && tpl.has("columns") ? tpl : null));

        JsonNode pgNode = n.has("pagination") ? n.get("pagination")
                : (tpl != null ? tpl.get("pagination") : null);
        Pagination pagination = null;
        if (pgNode != null && !pgNode.isNull()) {
            String countSql = pgNode.hasNonNull("countSql")
                    ? expandVars(id, pgNode.get("countSql").asText(), vars) : null;
            pagination = new Pagination(
                    pgNode.path("style").asText("offset"),
                    pgNode.path("pageParam").asText("pageNo"),
                    pgNode.path("sizeParam").asText("pageSize"),
                    pgNode.path("offsetParam").asText("offset"),
                    countSql);
        }

        String description = n.hasNonNull("description") ? n.get("description").asText()
                : (tpl != null ? tpl.path("description").asText("") : "");

        return new Doc(id, sql, List.copyOf(byIndex.values()), List.copyOf(columns),
                description, pagination);
    }

    private static String sqlText(JsonNode n, String id) {
        JsonNode sqlNode = n.get("sql");
        if (sqlNode == null) {
            return "";
        }
        if (!sqlNode.isArray()) {
            return sqlNode.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode line : sqlNode) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.asText());
        }
        return sb.toString();
    }

    /** 编译期变量：{{table}} {{columns}} ... */
    private static String expandVars(String id, String s, Map<String, String> vars) {
        Matcher m = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String v = vars.get(key);
            if (v == null) {
                throw new SqlSpecException(id + ": 模板变量 {{" + key + "}} 未定义");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static void applyParams(JsonNode params, Map<Integer, Param> byIndex, Map<String, Param> byName) {
        if (params == null || !params.isArray()) {
            return;
        }
        int auto = 1;
        for (JsonNode p : params) {
            int idx = p.has("index") ? p.get("index").asInt() : auto;
            auto = idx + 1;
            List<String> enums = new ArrayList<>();
            JsonNode e = p.get("enum");
            if (e != null && e.isArray()) {
                for (JsonNode x : e) {
                    enums.add(x.asText());
                }
            }
            Param old = byIndex.get(idx);
            String name = p.has("name") ? p.get("name").asText()
                    : (old != null ? old.name() : "p" + idx);
            String type = p.has("type") ? p.get("type").asText().toLowerCase()
                    : (old != null ? old.type() : "string");
            String desc = p.has("desc") ? p.get("desc").asText()
                    : (old != null ? old.desc() : "");
            Param param = new Param(idx, name, type, desc, List.copyOf(enums),
                    p.path("required").asBoolean(old != null && old.required()),
                    p.has("default") ? MAPPER.convertValue(p.get("default"), Object.class)
                            : (old != null ? old.defaultValue() : null));
            byIndex.put(idx, param);
            byName.put(name, param);
        }
    }

    private static List<String> readColumns(JsonNode n) {
        List<String> columns = new ArrayList<>();
        if (n == null) {
            return columns;
        }
        JsonNode cols = n.get("columns");
        if (cols != null && cols.isArray()) {
            for (JsonNode c : cols) {
                columns.add(c.asText());
            }
        }
        return columns;
    }

    /**
     * 全部语句 id（含命名空间，如 {@code user.findPage}），按定义顺序返回。
     *
     * @return 只读视图，修改它不会影响引擎
     */
    public Set<String> ids() {
        return docs.keySet();
    }

    /**
     * 取一条语句的原始定义，不渲染。
     *
     * <p>用于展示（「这条 SQL 长什么样、有哪些参数」）与校验（检查编号是否连续）。
     *
     * @param id 语句 id，必须带命名空间
     * @return 定义，永不为 null
     * @throws SqlSpecException 语句不存在
     */
    public Doc doc(String id) {
        Doc d = docs.get(id);
        if (d == null) {
            throw new SqlSpecException("未定义的语句: " + id + "，可用: " + docs.keySet());
        }
        return d;
    }

    /**
     * 渲染一条语句：填参数、做类型转换与白名单校验、拼出最终 SQL。
     *
     * <p>参数取值优先级：完整路径（{@code item2.status}）→ 同名字段（{@code status}）
     * → 实体运行时推断。这个顺序让「通用语句 + 局部覆盖」成为可能，
     * 不用为每个实体复制一份 SQL。
     *
     * <p>缺失的非必填参数会被当成 null 渲染，配合 {@code (#1 IS NULL OR col = #1)}
     * 就是「可选条件」；必填参数缺失直接抛异常，避免把条件静默丢掉导致全表扫描。
     *
     * @param id   语句 id
     * @param args 参数，key 为参数名或 {@code item}；可为 null（视为空）
     * @return 参数化 SQL 与参数值
     * @throws SqlSpecException 语句不存在、必填参数缺失、类型/白名单校验失败
     */
    public Prepared prepare(String id, Map<String, Object> args) {
        Doc doc = doc(id);
        List<Object> outArgs = new ArrayList<>();
        String sql = renderRaw(doc, doc.sql(), args == null ? Map.of() : args, outArgs);
        return new Prepared(sql, outArgs);
    }

    /**
     * 分页编译：一次给出「取数据」和「取总数」两条 SQL，参数各自对齐。
     * count 的 SQL 默认从主 SQL 推导（替换 select 列表、去掉 order by），
     * 含 GROUP BY / DISTINCT 时会拒绝推导，需要在 pagination.countSql 里显式写。
     */
    public PagePrepared preparePage(String id, Map<String, Object> args) {
        Doc d = doc(id);
        if (d.pagination() == null) {
            throw new SqlSpecException(id + ": 未声明 pagination，无法分页");
        }
        Pagination pg = d.pagination();
        Map<String, Object> a = args == null ? Map.of() : args;

        int size = Math.max(1, intOf(a.get(pg.sizeParam()), 20));
        int pageNo;
        int offset;
        if ("page".equalsIgnoreCase(pg.style())) {
            pageNo = Math.max(1, intOf(a.get(pg.pageParam()), 1));
            offset = (pageNo - 1) * size;
        } else {
            offset = Math.max(0, intOf(a.get(pg.offsetParam()), 0));
            pageNo = offset / size + 1;
        }

        List<Object> listArgs = new ArrayList<>();
        String base = renderRaw(d, d.sql(), a, listArgs);

        List<Object> countArgs = new ArrayList<>();
        String countSql;
        if (pg.countSql() != null) {
            countSql = renderRaw(d, pg.countSql(), a, countArgs);
        } else {
            countSql = deriveCount(base);
            countArgs.addAll(listArgs);
        }

        String listSql = appendPaging(base, size, offset);
        return new PagePrepared(listSql, listArgs, countSql, countArgs, pageNo, size);
    }

    private String renderRaw(Doc doc, String sqlText, Map<String, Object> a, List<Object> outArgs) {
        Regions regions = detectRegions(sqlText);
        StringBuilder out = new StringBuilder();
        String sql = sqlText;

        int i = 0;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (ch == '#' && i + 1 < sql.length()) {
                char next = sql.charAt(i + 1);
                if (next == '{') {
                    int close = sql.indexOf('}', i + 2);
                    if (close < 0) {
                        throw new SqlSpecException(doc.id() + ": 占位符没有闭合");
                    }
                    String expr = sql.substring(i + 2, close).trim();
                    out.append(expandNamed(doc, expr, i, regions, a, outArgs));
                    i = close + 1;
                    continue;
                }
                if (Character.isDigit(next)) {
                    int j = i + 1;
                    while (j < sql.length() && Character.isDigit(sql.charAt(j))) {
                        j++;
                    }
                    int index = Integer.parseInt(sql.substring(i + 1, j));
                    out.append(expandPositional(doc, index, a, outArgs));
                    i = j;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        String rendered = out.toString();
        return normalize(rewriter.apply(rendered));
    }

    /** 从主 SQL 推导 count：select 列表换成 count(*)，去掉 order by */
    static String deriveCount(String sql) {
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains(" GROUP BY ") || upper.contains(" DISTINCT ")) {
            throw new SqlSpecException("含 GROUP BY / DISTINCT 的语句无法自动推导 count，"
                    + "请在 pagination.countSql 里显式指定");
        }
        int sel = upper.indexOf("SELECT");
        int from = sel < 0 ? -1 : upper.indexOf(" FROM ", sel);
        if (sel < 0 || from < 0) {
            throw new SqlSpecException("无法推导 count，语句里找不到 SELECT ... FROM");
        }
        String head = sql.substring(0, sel) + "SELECT COUNT(*)";
        String rest = sql.substring(from);
        int ob = rest.toUpperCase(java.util.Locale.ROOT).indexOf(" ORDER BY ");
        if (ob >= 0) {
            rest = rest.substring(0, ob);
        }
        return normalize(head + rest);
    }

    /** 追加分页子句；SQL 里已经自己写了 limit 就不追加，交给语句控制 */
    static String appendPaging(String sql, int size, int offset) {
        if (Pattern.compile("(?i)\\blimit\\b").matcher(sql).find()
                || Pattern.compile("(?i)\\bfetch\\s+first\\b").matcher(sql).find()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql);
        sb.append(" LIMIT ").append(size);
        if (offset > 0) {
            sb.append(" OFFSET ").append(offset);
        }
        return sb.toString();
    }

    private static int intOf(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // ---------------- 占位符展开 ----------------

    private String expandPositional(Doc doc, int index, Map<String, Object> args, List<Object> outArgs) {
        Param p = doc.params().stream()
                .filter(x -> x.index() == index)
                .findFirst()
                .orElseThrow(() -> new SqlSpecException(doc.id() + ": 没有定义 #" + index));
        Object v = args.containsKey(p.name()) ? args.get(p.name()) : p.defaultValue();
        if (v == null && p.required()) {
            throw new SqlSpecException(doc.id() + ": #" + index + "(" + p.name() + ") 必填");
        }
        return emit(p, v, outArgs, doc);
    }

    private String expandNamed(Doc doc, String expr, int offset, Regions regions,
                               Map<String, Object> args, List<Object> outArgs) {
        // 整实体注入：item / item1 / item2 ...
        if (expr.matches("item\\d*")) {
            if (!args.containsKey(expr)) {
                throw new SqlSpecException(doc.id() + ": 缺少实体参数 " + expr);
            }
            return expandWholeItem(doc, expr, args.get(expr), offset, regions, outArgs);
        }

        // 实体属性：item.age / item1.name
        int dot = expr.indexOf('.');
        if (dot > 0) {
            String head = expr.substring(0, dot);
            String field = expr.substring(dot + 1);
            if (head.matches("item\\d*")) {
                Object bean = args.get(head);
                if (bean == null) {
                    throw new SqlSpecException(doc.id() + ": 缺少实体参数 " + head);
                }
                Object v = prop(bean, field);
                // 优先级：完整表达式描述 > 同名字段描述 > 运行时类型
                Param p = doc.params().stream()
                        .filter(x -> x.name().equals(expr) || x.name().equals(field))
                        .findFirst().orElse(null);
                if (p != null) {
                    return emit(p, v, outArgs, doc);
                }
                outArgs.add(v instanceof Like lk ? lk.toPattern() : v);
                return "?";
            }
            // a.b 嵌套普通参数
            Object v = args;
            for (String seg : expr.split("\\.")) {
                v = prop(v, seg);
                if (v == null) {
                    break;
                }
            }
            outArgs.add(v);
            return "?";
        }

        // 普通命名参数，也允许被 params 描述增强（enum / 类型）
        Object v = args.get(expr);
        Param p = doc.params().stream().filter(x -> x.name().equals(expr)).findFirst().orElse(null);
        if (p != null) {
            if (v == null) {
                v = p.defaultValue();
            }
            return emit(p, v, outArgs, doc);
        }
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
                outArgs.add(o);
            }
            return sb.toString();
        }
        outArgs.add(v instanceof Like lk ? lk.toPattern() : v);
        return "?";
    }

    private String expandWholeItem(Doc doc, String var, Object bean, int offset, Regions r, List<Object> outArgs) {
        List<String> base = doc.columns().isEmpty() ? deriveColumns(bean) : doc.columns();
        // 只注入有值的字段：这样一条 update 语句既能全量更新，也能只改传了的字段
        List<String> cols = new ArrayList<>();
        for (String c : base) {
            if (itemValue(bean, c) != null) {
                cols.add(c);
            }
        }
        if (cols.isEmpty()) {
            throw new SqlSpecException(doc.id() + ": " + var + " 没有可注入的字段");
        }
        if (r.inColumns(offset)) {
            return String.join(", ", cols);
        }
        if (r.inValues(offset)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('?');
                outArgs.add(itemValue(bean, cols.get(i)));
            }
            return sb.toString();
        }
        if (r.inSet(offset)) {
            StringBuilder sb = new StringBuilder();
            for (String c : cols) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(c).append(" = ?");
                outArgs.add(itemValue(bean, c));
            }
            return sb.toString();
        }
        throw new SqlSpecException(doc.id() + ": 无法判断 #{" + var + "} 的注入位置，"
                + "只能出现在 INSERT 列/值区或 UPDATE 的 SET 区");
    }

    /** 整实体注入时取属性：先按列名，再按驼峰字段名（user_name -> userName） */
    public static Object itemValue(Object bean, String column) {
        Object v = prop(bean, column);
        if (v == null && !(bean instanceof Map)) {
            v = prop(bean, camel(column));
        }
        return v;
    }

    static String camel(String column) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 按描述里的 type 决定是参数化还是直接拼接 */
    private String emit(Param p, Object v, List<Object> outArgs, Doc doc) {
        // enum 属于"字段级描述"，对任何 type 都生效
        if (!p.enumValues().isEmpty()) {
            String s = v == null ? null : String.valueOf(v);
            if (s == null || !p.enumValues().contains(s)) {
                throw new SqlSpecException(doc.id() + ": " + p.name() + "=" + s
                        + " 不在白名单 " + p.enumValues());
            }
        }
        switch (p.type()) {
            case "ident" -> {
                String s = String.valueOf(v);
                if (v == null || !IDENT.matcher(s).matches()) {
                    throw new SqlSpecException(doc.id() + ": 非法标识符 " + s);
                }
                if (!p.enumValues().isEmpty() && !p.enumValues().contains(s)) {
                    throw new SqlSpecException(doc.id() + ": " + s + " 不在白名单 " + p.enumValues());
                }
                return s;
            }
            case "raw" -> {
                String s = String.valueOf(v);
                if (!DIGITS.matcher(s).matches()) {
                    throw new SqlSpecException(doc.id() + ": raw 只接受整数，收到 " + s);
                }
                return s;
            }
            // 模糊匹配：值仍然参数化，只是多一步「转义 + 包装模式串」
            case "like", "likeprefix", "likesuffix" -> {
                outArgs.add(likeValue(p.type(), v));
                return "?";
            }
            default -> {
                // 调用方直接传 Like 对象也算声明了意图（不必再在 JSON 里写 type）
                if (v instanceof Like like) {
                    outArgs.add(like.toPattern());
                    return "?";
                }
                outArgs.add(convert(p, v, doc));
                return "?";
            }
        }
    }

    /**
     * 把 like 参数转成可绑定的模式串。
     *
     * <p>三条规则：
     * <ul>
     *   <li>值为 null 原样返回——配合 {@code (#1 IS NULL OR col LIKE #1)} 表示「不加这个条件」；</li>
     *   <li>值为空串转成 null——空搜索框不应该退化成 {@code LIKE '%%'} 的全表扫描；</li>
     *   <li>其余一律先转义再按模式包装，用户输入的 {@code %} / {@code _} 只按字面匹配。</li>
     * </ul>
     *
     * @param type like / likeprefix / likesuffix
     * @param v    原始值，可能是 String 或 {@link Like}
     * @return 可绑定的对象；null 表示该条件不生效
     */
    private static Object likeValue(String type, Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Like like) {
            return like.toPattern();
        }
        String s = String.valueOf(v);
        if (s.isEmpty()) {
            return null;
        }
        String escaped = Like.escape(s);
        return switch (type) {
            case "likeprefix" -> escaped + "%";
            case "likesuffix" -> "%" + escaped;
            default -> "%" + escaped + "%";
        };
    }

    @SuppressWarnings("unchecked")
    private Object convert(Param p, Object v, Doc doc) {
        if (v == null) {
            return null;
        }
        try {
            return switch (p.type()) {
                case "int", "integer" -> v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
                case "long" -> v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
                case "decimal", "number", "bigdecimal" -> v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
                case "bool", "boolean" -> v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
                case "datetime" -> v instanceof LocalDateTime t ? t
                        : LocalDateTime.parse(v.toString().replace(' ', 'T'));
                case "date" -> v instanceof LocalDate d ? d : LocalDate.parse(v.toString().substring(0, 10));
                case "string" -> String.valueOf(v);
                default -> v;
            };
        } catch (RuntimeException e) {
            throw new SqlSpecException(doc.id() + ": 参数 " + p.name() + " 转 " + p.type() + " 失败: " + v);
        }
    }

    // ---------------- 区域识别 ----------------

    private record Regions(int colStart, int colEnd, int valStart, int valEnd, int setStart, int setEnd) {
        boolean inColumns(int p) {
            return colStart >= 0 && p > colStart && p < colEnd;
        }

        boolean inValues(int p) {
            return valStart >= 0 && p > valStart && p < valEnd;
        }

        boolean inSet(int p) {
            return setStart >= 0 && p > setStart && p < setEnd;
        }
    }

    private static Regions detectRegions(String sql) {
        int colStart = -1;
        int colEnd = -1;
        int valStart = -1;
        int valEnd = -1;
        int setStart = -1;
        int setEnd = -1;

        Matcher ins = Pattern.compile("(?i)\\binsert\\s+into\\s+[\\w.\"`]+\\s*\\(").matcher(sql);
        if (ins.find()) {
            int open = ins.end() - 1;
            colStart = open;
            colEnd = matchParen(sql, open);
        }
        Matcher vals = Pattern.compile("(?i)\\bvalues\\s*\\(").matcher(sql);
        if (vals.find()) {
            int open = vals.end() - 1;
            valStart = open;
            valEnd = matchParen(sql, open);
        }
        Matcher set = Pattern.compile("(?i)\\bset\\b").matcher(sql);
        if (set.find()) {
            setStart = set.end();
            Matcher where = Pattern.compile("(?i)\\bwhere\\b").matcher(sql);
            setEnd = where.find() ? where.start() : sql.length();
        }
        return new Regions(colStart, colEnd, valStart, valEnd, setStart, setEnd);
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new SqlSpecException("括号没有闭合");
    }

    // ---------------- 工具 ----------------

    private static List<String> deriveColumns(Object bean) {
        List<String> cols = new ArrayList<>();
        if (bean instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) {
                    cols.add(String.valueOf(e.getKey()));
                }
            }
            return cols;
        }
        if (bean == null) {
            return cols;
        }
        Class<?> c = bean.getClass();
        Set<String> seen = new LinkedHashSet<>();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !seen.add(f.getName())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.get(bean) != null) {
                        cols.add(EntityDescriptor.snake(f.getName()));
                    }
                } catch (IllegalAccessException ignored) {
                    // 跳过不可读字段
                }
            }
            c = c.getSuperclass();
        }
        return cols;
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

    /** 把某条语句渲染后的样子打出来，便于排查 */
    public String explain(String id, Map<String, Object> args) {
        return prepare(id, args).toString();
    }
}
