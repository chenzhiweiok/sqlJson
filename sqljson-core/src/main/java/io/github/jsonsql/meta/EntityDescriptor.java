package io.github.jsonsql.meta;

import io.github.jsonsql.CurrentUser;
import io.github.jsonsql.SqlJsonRepository;
import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.meta.statement.StatementFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 实体的运行时描述：反射一次，得到表名、列清单、主键、逻辑删除列、审计列，
 * 并据此生成一套默认 CRUD 的 JSON——注意是 <b>JSON</b>，不是 SQL。
 *
 * <p>为什么不直接拼 SQL：如果这里绕过 {@link SqlV2} 自己拼，就会出现「注解版」和
 * 「手写 JSON 版」两套渲染逻辑，占位符规则、白名单、分页、逻辑删除改写都要维护两遍，
 * 早晚分叉。生成 JSON 再交给同一个编译引擎，等于把两条件收敛成一条。
 *
 * <p><b>反射成本</b>：只在装配阶段跑一次（{@link #of(Class)}），
 * 结果缓存在仓库实例里，运行期不再反射字段——除了读写值本身。
 *
 * <p><b>它不认识注解</b>：{@code @SqlJson} / {@code @RedisJson} 由各自的 {@link EntityParser}
 * 读取，解析结果统一收敛到这里。所以换存储后端时改的是解析器，不是这个类，
 * 也不会出现「第二份字段反射逻辑」与第一份慢慢分叉。
 *
 * <p>{@link Builder} 是给解析器用的公开 API：它只负责校验与装载，不做任何注解读取。
 */

public final class EntityDescriptor {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private final Class<?> type;
    private final String namespace;
    private final String table;

    private final Field idField;
    private final String idColumn;
    private final boolean idGenerated;

    /** 逻辑删除列，null 表示没有 */
    private final String deletedColumn;

    /** 审计列，null 表示没有 */
    private final String createdAtColumn;
    private final String updatedAtColumn;
    private final String createdByColumn;
    private final String updatedByColumn;

    private final Field createdAtField;
    private final Field updatedAtField;
    private final Field createdByField;
    private final Field updatedByField;

    /** 全部参与映射的列 */
    private final List<Col> columns;

    /** 注解上声明的扩展语句工厂，装配时与内置六条合并 */
    private final List<StatementFactory> extraStatements;

    /** 列元数据：字段、列名、是否主键。只承载数据，所以用 record */
    public record Col(Field field, String column, boolean id) {
    }

    /** @return 实体类型 */
    public Class<?> type() {
        return type;
    }

    /** @return 命名空间，也是语句 id 前缀 */
    public String namespace() {
        return namespace;
    }

    /** @return 物理表名 */
    public String table() {
        return table;
    }

    /** @return 主键字段 */
    public Field idField() {
        return idField;
    }

    /** @return 主键列名 */
    public String idColumn() {
        return idColumn;
    }

    /** @return 主键是否由数据库生成 */
    public boolean idGenerated() {
        return idGenerated;
    }

    /** @return 逻辑删除列；null 表示没有 */
    public String deletedColumn() {
        return deletedColumn;
    }

    /** @return 创建时间列；null 表示没有 */
    public String createdAtColumn() {
        return createdAtColumn;
    }

    /** @return 更新时间列；null 表示没有 */
    public String updatedAtColumn() {
        return updatedAtColumn;
    }

    /** @return 创建人列；null 表示没有 */
    public String createdByColumn() {
        return createdByColumn;
    }

    /** @return 更新人列；null 表示没有 */
    public String updatedByColumn() {
        return updatedByColumn;
    }

    /** @return 全部参与映射的列，顺序与字段声明一致 */
    public List<Col> columns() {
        return columns;
    }

    private EntityDescriptor(Builder b) {
        this.type = b.type;
        this.namespace = b.namespace;
        this.table = b.table;
        this.idField = b.idField;
        this.idColumn = b.idColumn;
        this.idGenerated = b.idGenerated;
        this.deletedColumn = b.deletedColumn;
        this.createdAtColumn = b.createdAtColumn;
        this.updatedAtColumn = b.updatedAtColumn;
        this.createdByColumn = b.createdByColumn;
        this.updatedByColumn = b.updatedByColumn;
        this.createdAtField = b.createdAtField;
        this.updatedAtField = b.updatedAtField;
        this.createdByField = b.createdByField;
        this.updatedByField = b.updatedByField;
        this.columns = List.copyOf(b.columns);
        this.extraStatements = List.copyOf(b.extraStatements);
    }

    /**
     * 审计列的种类。
     *
     * <p>用枚举而不是四个布尔开关，是因为这些列在语义上互斥地归属不同角色，
     * 而且 {@link Builder#audit(Field, String, AuditKind)} 的调用点读起来能直接看出意图。
     */
    public enum AuditKind {
        /** 创建时间 */
        CREATED_AT,
        /** 更新时间 */
        UPDATED_AT,
        /** 创建人 */
        CREATED_BY,
        /** 更新人 */
        UPDATED_BY
    }

    /**
     * 创建一个元数据构建器，给注解解析器用。
     *
     * <p>业务代码不需要它——业务只写 {@code @SqlJson}，由 {@link SqlJsonParser} 负责翻译。
     * 实现新的存储注解时才会用到这个入口。
     *
     * @param type 实体类
     * @return 新的构建器
     */
    public static Builder builder(Class<?> type) {
        return new Builder(type);
    }

    /**
     * 实体元数据构建器。
     *
     * <p>把「存储容器的差异」和「列的语义」分开：容器（表名 / 集合名 / 键前缀）由解析器
     * 一次性给出，列的语义（主键、审计、逻辑删除）是各存储共通的，走同一套方法。
     *
     * <p><b>调用顺序无关</b>：{@code column()} / {@code id()} / {@code softDelete()} /
     * {@code audit()} 可以任意顺序调用，后调用的会补齐或改写同一列，不会产生重复列。
     * 这样解析器写起来不必先判断「这个字段是不是主键」再决定调哪个方法。
     *
     * <p><b>非线程安全，也不该复用</b>：一次装配一个实例，构建完即丢弃。
     * 产出的 {@link EntityDescriptor} 才是不可变、可共享的。
     */
    public static final class Builder {

        private final Class<?> type;
        private String namespace;
        private String table;
        private Field idField;
        private String idColumn = "id";
        private boolean idGenerated;
        private String deletedColumn;
        private String createdAtColumn;
        private String updatedAtColumn;
        private String createdByColumn;
        private String updatedByColumn;
        private Field createdAtField;
        private Field updatedAtField;
        private Field createdByField;
        private Field updatedByField;
        private final List<Col> columns = new ArrayList<>();
        private final List<StatementFactory> extraStatements = new ArrayList<>();

        private Builder(Class<?> type) {
            this.type = type;
        }

        /**
         * 命名空间，语句 id 的前缀（{@code user.findById}）。
         *
         * @param namespace 非空，通常取注解的 {@code value}
         * @return this
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace == null ? null : namespace.trim();
            return this;
        }

        /**
         * 存储容器：SQL 里是表名，文档库是集合名，KV 是键前缀。
         *
         * <p>不传或传空时，{@link #build()} 会退化成 {@code snake(namespace)}，
         * 这正好覆盖「表名与命名空间同名」这个最常见的情况。
         *
         * @param container 容器名；null 或空白表示用默认值
         * @return this
         */
        public Builder container(String container) {
            if (container != null && !container.isBlank()) {
                this.table = container.trim();
            }
            return this;
        }

        /**
         * 没有字段被标为主键时使用的列名。
         *
         * <p>先声明、后被覆盖：一旦 {@link #id(Field, String, boolean)} 被调用，
         * 这里的值就失效。所以它只是兜底，不是约束。
         *
         * @param idColumn 主键列名；null 或空白忽略
         * @return this
         */
        public Builder idColumn(String idColumn) {
            if (idColumn != null && !idColumn.isBlank()) {
                this.idColumn = idColumn.trim();
            }
            return this;
        }

        /**
         * 声明一个普通列，列名按字段名转 snake_case。
         *
         * @param field 字段，会被设为可访问
         * @return this
         */
        public Builder column(Field field) {
            ensure(field, null);
            return this;
        }

        /**
         * 声明一个普通列，显式指定列名。
         *
         * <p>Mongo / Redis 的解析器通常传字段原名（{@code userName}），
         * 关系库解析器传 snake_case（{@code user_name}）。
         *
         * @param field      字段
         * @param columnName 列名；null 或空白时按字段名转 snake_case
         * @return this
         */
        public Builder column(Field field, String columnName) {
            ensure(field, columnName);
            return this;
        }

        /**
         * 声明主键，不指定列名。
         *
         * @param field     主键字段
         * @param generated 是否由存储生成（自增或由数据库分配）
         * @return this
         */
        public Builder id(Field field, boolean generated) {
            return id(field, null, generated);
        }

        /**
         * 声明主键。
         *
         * <p>主键字段同时也会作为普通列加进 {@link #columns}——查询要选它、更新要用它
         * 做 WHERE，不能被排除。
         *
         * @param field     主键字段
         * @param columnName 列名；null 或空白时按字段名转 snake_case
         * @param generated 是否由存储生成
         * @return this
         */
        public Builder id(Field field, String columnName, boolean generated) {
            Col c = ensure(field, columnName);
            markId(field, c.column());
            this.idField = field;
            this.idColumn = c.column();
            this.idGenerated = generated;
            return this;
        }

        /**
         * 声明逻辑删除列。
         *
         * <p>是否真的启用逻辑删除由仓库决定（SQL 里是改写 SQL，文档库可能是自定义查询），
         * 元数据只负责「哪一列代表删除状态」。
         *
         * @param field      字段
         * @param columnName 列名；null 时按字段名转 snake_case
         * @return this
         */
        public Builder softDelete(Field field, String columnName) {
            this.deletedColumn = ensure(field, columnName).column();
            return this;
        }

        /**
         * 声明审计列。
         *
         * @param field      字段
         * @param columnName 列名；null 时按字段名转 snake_case
         * @param kind       列的角色（创建时间 / 更新时间 / 创建人 / 更新人）
         * @return this
         */
        public Builder audit(Field field, String columnName, AuditKind kind) {
            String column = ensure(field, columnName).column();
            switch (kind) {
                case CREATED_AT -> {
                    this.createdAtColumn = column;
                    this.createdAtField = field;
                }
                case UPDATED_AT -> {
                    this.updatedAtColumn = column;
                    this.updatedAtField = field;
                }
                case CREATED_BY -> {
                    this.createdByColumn = column;
                    this.createdByField = field;
                }
                case UPDATED_BY -> {
                    this.updatedByColumn = column;
                    this.updatedByField = field;
                }
            }
            return this;
        }

        /**
         * 附加一条默认语句生成策略。
         *
         * <p>只对 SQL 后端有意义；其它后端可以忽略，或用自己的方式复用。
         *
         * @param factory 语句工厂；null 忽略
         * @return this
         */
        public Builder statement(StatementFactory factory) {
            if (factory != null) {
                extraStatements.add(factory);
            }
            return this;
        }

        /**
         * 产出不可变元数据。
         *
         * <p>三项校验：namespace 非空、容器名有默认值、主键存在。
         * 放在这里而不是每个解析器里，是为了保证不同后端抛出的错误信息一致——
         * 「这个实体为什么装配不起来」不该因为换了注解就换成另一套说法。
         *
         * @return 元数据
         * @throws SqlSpecException 缺少 namespace 或主键
         */
        public EntityDescriptor build() {
            if (namespace == null || namespace.isBlank()) {
                throw new SqlSpecException(type.getName() + ": namespace 不能为空");
            }
            if (table == null || table.isBlank()) {
                table = snake(namespace);
            }
            if (idField == null) {
                throw new SqlSpecException(type.getName()
                        + " 找不到主键字段（标 @SqlColumn(id = true)、用名为 id 的字段，或解析器显式调用 id(...)）");
            }
            return new EntityDescriptor(this);
        }

        /** 取或建列，顺便把字段设为可访问；重名不算错，按字段去重 */
        private Col ensure(Field field, String columnName) {
            String name = columnName == null || columnName.isBlank()
                    ? snake(field.getName())
                    : columnName.trim();
            field.setAccessible(true);
            for (int i = 0; i < columns.size(); i++) {
                Col c = columns.get(i);
                if (c.field().equals(field)) {
                    if (c.column().equals(name)) {
                        return c;
                    }
                    Col renamed = new Col(field, name, c.id());
                    columns.set(i, renamed);
                    return renamed;
                }
            }
            Col created = new Col(field, name, false);
            columns.add(created);
            return created;
        }

        /** 把已有列标为主键；列一定已由 {@link #ensure} 建好 */
        private void markId(Field field, String column) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).field().equals(field)) {
                    columns.set(i, new Col(field, column, true));
                    return;
                }
            }
        }
    }

    /**
     * 解析实体：按类上的注解找到合适的解析器。
     *
     * <p>这是业务代码的默认入口，也是「注解可扩展」的体现——新增 {@code @RedisJson}
     * 之后，这里一行都不用改。查找规则见 {@link EntityParsers#parse(Class)}：
     * 命中零个或多个都会报错，不做静默兜底。
     *
     * @param type 带存储注解的实体类
     * @return 实体元数据
     * @throws SqlSpecException 没有可识别的注解，或元数据不完整
     */
    public static EntityDescriptor of(Class<?> type) {
        return EntityParsers.parse(type);
    }

    public boolean softDelete() {
        return deletedColumn != null;
    }

    public List<String> allColumnNames() {
        List<String> out = new ArrayList<>();
        for (Col c : columns) {
            out.add(c.column());
        }
        return out;
    }

    /** 可写列：insert 用（含非自增主键，排除逻辑删除列） */
    public List<String> insertColumns() {
        List<String> out = new ArrayList<>();
        for (Col c : columns) {
            if (c.id() && idGenerated) {
                continue;
            }
            if (c.column().equals(deletedColumn)) {
                continue;
            }
            out.add(c.column());
        }
        return out;
    }

    /** 可更新列：update 用（排除主键与逻辑删除列，后者由 restore 专门改写） */
    public List<String> updateColumns() {
        List<String> out = new ArrayList<>();
        for (Col c : columns) {
            if (c.id() || c.column().equals(deletedColumn)) {
                continue;
            }
            out.add(c.column());
        }
        return out;
    }

    /** 审计字段写入实体（只在为空时填，不覆盖调用方显式传的值） */
    public void fillAudit(Object bean, boolean insert) {
        LocalDateTime now = LocalDateTime.now();
        String user = CurrentUser.get();
        if (insert) {
            setIfNull(createdAtField, bean, now);
            setIfNull(createdByField, bean, user);
        }
        setIfNull(updatedAtField, bean, now);
        setIfNull(updatedByField, bean, user);
        // 更新场景下 updatedAt 应该刷新，而不是保持旧值
        if (!insert) {
            setForce(updatedAtField, bean, now);
            setForce(updatedByField, bean, user);
        }
    }

    private static void setIfNull(Field f, Object bean, Object value) {
        if (f == null) {
            return;
        }
        try {
            if (f.get(bean) == null) {
                f.set(bean, value);
            }
        } catch (IllegalAccessException ignored) {
            // 忽略，交给后续 SQL 报错
        }
    }

    private static void setForce(Field f, Object bean, Object value) {
        if (f == null) {
            return;
        }
        try {
            f.set(bean, value);
        } catch (IllegalAccessException ignored) {
            // 忽略
        }
    }

    /**
     * 生成默认语句：内置六条 + 注解上声明的扩展。
     *
     * <p>这个类只回答「实体长什么样」，不回答「语句怎么拼」——后者在
     * {@link DefaultStatementSet} 与 {@link StatementFactory} 的实现里。
     *
     * <p>产物是 v2 格式的 JSON，与手写语句走同一条编译路径。
     *
     * @return 命名空间节点，key 为语句名
     */
    public ObjectNode defaultStatements() {
        return DefaultStatementSet.buildWith(this, extraStatements);
    }

    /**
     * 生成默认语句，并追加调用方传入的扩展（优先级高于注解声明）。
     *
     * <p>用于「同一个实体在不同场景需要不同默认语句」的情况，例如测试里
     * 临时插一条统计语句，不想为它单独建一个注解。
     *
     * @param extra 额外工厂；与已有同名时覆盖，null 或空表示不加
     * @return 命名空间节点
     */
    public ObjectNode defaultStatements(java.util.List<StatementFactory> extra) {
        java.util.List<StatementFactory> all = new ArrayList<>(extraStatements);
        if (extra != null) {
            all.addAll(extra);
        }
        return DefaultStatementSet.buildWith(this, all);
    }

    /** 反射实例化扩展工厂；只在这里做一次，所以失败就直接抛 */
    private static StatementFactory instantiate(Class<? extends StatementFactory> factoryType, Class<?> entityType) {
        try {
            var ctor = factoryType.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SqlSpecException(entityType.getSimpleName() + " 声明的语句工厂无法实例化: "
                    + factoryType.getName() + "（需要 public 无参构造）");
        }
    }

    public static String snake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
