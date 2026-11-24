package io.github.jsonsql.meta;

import io.github.jsonsql.SqlColumn;
import io.github.jsonsql.SqlJson;
import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.meta.statement.StatementFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * {@link SqlJson} 的解析器：把注解 + 字段反射成关系型存储的元数据。
 *
 * <p>它做三件事，顺序固定：
 * <ol>
 *   <li>读注解上的 namespace、表名、主键列名、扩展语句工厂；</li>
 *   <li>遍历字段，按 {@link SqlColumn} 决定列名与语义（主键 / 逻辑删除 / 审计）；</li>
 *   <li>交给 {@link EntityDescriptor.Builder} 做校验并产出不可变元数据。</li>
 * </ol>
 *
 * <p><b>为什么列名默认转 snake_case</b>：Java 字段是 {@code userName}，SQL 列习惯
 * {@code user_name}。这是关系库的约定，不是框架的偏好——所以它属于「SQL 解析器」，
 * 而不是「元数据」。Mongo 的解析器完全可以让列名保持 camelCase。
 *
 * <p><b>隐式约定</b>（不写注解也能工作，但要知道它在发生）：
 * 字段名 {@code id} 自动当主键；列名 {@code deleted}、{@code created_at}、
 * {@code updated_at}、{@code created_by}、{@code updated_by} 自动获得对应语义。
 * 这些约定只在 SQL 这一侧成立——换成 Redis 时不该假设有 {@code deleted} 列，
 * 所以它们写在解析器里，而不是 {@code EntityDescriptor} 里。
 */
public final class SqlJsonParser implements EntityParser<SqlJson> {

    @Override
    public Class<SqlJson> annotationType() {
        return SqlJson.class;
    }

    @Override
    public EntityDescriptor parse(Class<?> type, SqlJson ann) {
        EntityDescriptor.Builder b = EntityDescriptor.builder(type)
                .namespace(ann.value())
                .container(ann.table())
                .idColumn(ann.idColumn());

        // 注解声明的扩展语句：装配期实例化一次，失败立刻报错，不拖到查询时
        for (Class<? extends StatementFactory> factoryType : ann.statements()) {
            b.statement(instantiate(factoryType, type));
        }

        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            SqlColumn fc = f.getAnnotation(SqlColumn.class);
            if (fc != null && fc.skip()) {
                continue;
            }
            String column = fc != null && !fc.value().isBlank()
                    ? fc.value()
                    : EntityDescriptor.snake(f.getName());
            boolean isId = (fc != null && fc.id()) || f.getName().equals("id");

            if (isId) {
                b.id(f, column, fc != null && fc.generated());
            } else {
                b.column(f, column);
            }

            if ((fc != null && fc.deleted()) || "deleted".equals(column)) {
                b.softDelete(f, column);
            }
            if ((fc != null && fc.createdAt()) || "created_at".equals(column)) {
                b.audit(f, column, EntityDescriptor.AuditKind.CREATED_AT);
            }
            if ((fc != null && fc.updatedAt()) || "updated_at".equals(column)) {
                b.audit(f, column, EntityDescriptor.AuditKind.UPDATED_AT);
            }
            if ((fc != null && fc.createdBy()) || "created_by".equals(column)) {
                b.audit(f, column, EntityDescriptor.AuditKind.CREATED_BY);
            }
            if ((fc != null && fc.updatedBy()) || "updated_by".equals(column)) {
                b.audit(f, column, EntityDescriptor.AuditKind.UPDATED_BY);
            }
        }
        return b.build();
    }

    /** 反射实例化扩展语句工厂；失败直接抛，因为这是配置错误，不是运行期异常 */
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
}
