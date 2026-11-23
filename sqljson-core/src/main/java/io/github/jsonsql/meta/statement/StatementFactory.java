package io.github.jsonsql.meta.statement;

import io.github.jsonsql.SqlJson;
import io.github.jsonsql.SqlJsonRepository;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.meta.EntityDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认语句的生成策略：一条语句一个实现。
 *
 * <p>把「生成哪几条默认语句」和「每条语句长什么样」分开，是这个接口存在的全部理由。
 * 之前的写法是一个方法里连拼六条，结果是一处改常量、六条一起受影响，
 * 而且没法只测其中一条。
 *
 * <p><b>为什么产物是 JSON 而不是 SQL</b>：这些语句要和手写 JSON 走同一个编译引擎
 * （{@link SqlV2}），凡是在这里直接拼 SQL 的地方，都会长出一套并行的渲染逻辑。
 *
 * <p><b>为什么不自动发现</b>：类路径、{@code META-INF/services}、构造器可见性，
 * 任何一处错都会静默失败，「我明明写了实现，为什么没生效」会变成排查噩梦。
 * 显式声明错了至少能在代码里搜到那个类。
 *
 * <p><b>两种声明方式</b>：注解 {@code @SqlJson(statements = ...)} 跟着实体走，适合固定扩展；
 * {@link SqlJsonRepository#of(Class, javax.sql.DataSource, io.github.jsonsql.Dialect, java.util.List)}
 * 在运行期传入，适合按配置决定。同名即覆盖，但保留原位置。
 *
 * <p><b>优先级</b>：{@code @SqlJson(resource = ...)} 里的同名语句仍然最高——
 * 它能改配置、走发布流程，而实现这个接口要重新编译。临时调一条 SQL 不该逼人发版。
 */
public interface StatementFactory {

    /**
     * 语句名，会成为 {@code namespace.name} 的后半段。
     *
     * <p>必须与 {@link SqlJsonRepository} 里的调用点一致：{@code findById} 写成 {@code findOne}，
     * 框架的 {@code findById()} 就会找不到语句。重名会静默覆盖，所以默认集合里不允许重复。
     *
     * @return 语句名，小驼峰
     */
    String name();

    /**
     * 生成这条语句的 JSON 定义。
     *
     * @param entity 实体元数据，读表名、列清单、主键、逻辑删除列等
     * @return 一个完整的语句节点（含 {@code sql}，可选 {@code columns} / {@code pagination} / {@code description}）
     */
    ObjectNode build(EntityDescriptor entity);
}
