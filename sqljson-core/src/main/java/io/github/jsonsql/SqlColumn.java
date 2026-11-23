package io.github.jsonsql;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级映射声明：列名、主键、逻辑删除、审计字段。
 *
 * <p>不写这个注解也能用——字段名按「驼峰转下划线」自动映射（{@code userName → user_name}）。
 * 只有在这几种情况下才需要显式声明：
 * <ul>
 *   <li>列名与默认推导不一致（{@code @SqlColumn("u_name")}）</li>
 *   <li>要标记主键或自增</li>
 *   <li>要启用逻辑删除、审计字段这类框架行为</li>
 *   <li>某个字段不参与映射（如临时计算字段）</li>
 * </ul>
 *
 * <p><b>互斥关系</b>：{@code id / deleted / createdAt / updatedAt / createdBy / updatedBy}
 * 是六种角色，一个字段只应承担一种。同时标两个（例如既是 id 又是 deleted）不会报错，
 * 但行为未定义——框架按固定顺序判断，后写的角色可能被忽略。
 *
 * <p>示例：
 * <pre>{@code
 * public class SysUser {
 *     @SqlColumn(id = true, generated = true)
 *     Long id;                            // 主键，自增，insert 后回填
 *
 *     @SqlColumn("user_name")
 *     String userName;                    // 列名与字段名不同
 *
 *     @SqlColumn(deleted = true)
 *     Integer deleted;                    // 逻辑删除列
 *
 *     @SqlColumn(createdAt = true)
 *     LocalDateTime createdAt;            // insert 自动填
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlColumn {

    /**
     * 列名。
     *
     * @return 留空时按字段名转下划线推导（{@code userName → user_name}）
     */
    String value() default "";

    /**
     * 是否主键。
     *
     * @return true 表示这一列是主键，{@code findById / deleteById / updateById} 用它做条件
     */
    boolean id() default false;

    /**
     * 主键是否由数据库生成（自增或序列）。
     *
     * <p>为 true 时，insert 不把该列写进 SQL，执行后用 JDBC 返回的主键回填实体。
     * 手工指定主键（如雪花 ID）的场景应保持 false，否则插入值会被忽略。
     *
     * @return true 表示由数据库生成
     */
    boolean generated() default false;

    /**
     * 跳过映射：该字段既不读也不写。
     *
     * <p>用于实体上的临时字段、聚合结果字段（如 count），以及不该进 SQL 的敏感字段。
     *
     * @return true 表示不参与任何映射
     */
    boolean skip() default false;

    /**
     * 逻辑删除标记列。
     *
     * <p>声明后自动获得四个行为：查询追加 {@code <col> = 0}、
     * {@code deleteById} 改写为 {@code update ... set <col> = 1}、
     * 提供恢复与查询已删数据的能力。
     *
     * <p>列类型建议用整型（0/1）或布尔；框架只写 1、只比对 0，不做类型转换。
     *
     * @return true 表示这一列是逻辑删除标记
     */
    boolean deleted() default false;

    /**
     * 创建时间列：insert 时若实体上为空则填当前时间。
     *
     * @return true 表示这一列记录创建时间
     */
    boolean createdAt() default false;

    /**
     * 更新时间列：insert 与 update 时都填当前时间。
     *
     * <p>逻辑删除与恢复也会刷新这一列，这样回收站里能看到「什么时候被删的」。
     *
     * @return true 表示这一列记录最后修改时间
     */
    boolean updatedAt() default false;

    /**
     * 创建人列：insert 时若为空则取 {@link CurrentUser}。
     *
     * @return true 表示这一列记录创建人
     */
    boolean createdBy() default false;

    /**
     * 更新人列：insert 与 update 时取 {@link CurrentUser}。
     *
     * @return true 表示这一列记录最后修改人
     */
    boolean updatedBy() default false;
}
