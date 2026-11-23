package io.github.jsonsql.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jsonsql.Dialect;
import io.github.jsonsql.SqlJson;
import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.engine.SoftDeleteRewriter;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.statement.StatementFactory;
import io.github.jsonsql.support.SqlLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 装配器：把「一个带 {@link SqlJson} 的类」变成可执行的 {@link Assembled}。
 *
 * <p>它负责四件事，顺序不能换：
 * <ol>
 *   <li>读注解，交给 {@link EntityDescriptor} 得到元数据（字段、表名、主键、审计）；</li>
 *   <li>加载 {@code resource} 指向的 JSON，<b>资源优先</b>，默认语句只补缺；</li>
 *   <li>编译成 {@link SqlV2}；</li>
 *   <li>有逻辑删除列时，装改写器。</li>
 * </ol>
 *
 * <p><b>为什么单独成一个类</b>：这段逻辑是「启动期一次」的重量级操作，而仓库是请求期反复用的门面。
 * 混在一起的结果是——读仓库代码的人要在一堆 CRUD 方法里先看完资源加载，才看到第一个业务方法；
 * 而且想单独测「JSON 合并规则」必须先有 DataSource。
 * 拆开之后，装配只需要 classpath，<b>不需要数据库</b>。
 *
 * <p><b>顺序为什么不能换</b>：改写器必须在编译「之后」装，否则已编译的语句不受影响；
 * 而资源合并必须在编译「之前」，否则手写语句不会进引擎。这两步都是静默生效的，
 * 顺序错了不会报错，只会行为不对。
 */
public final class RepositoryAssembler {

    private RepositoryAssembler() {
    }

    /**
     * 装配。
     *
     * <p>重量级操作：读注解、反射字段、解析 JSON、编译全部语句。
     * 必须在启动期调一次并缓存结果，不要放在请求路径里。
     *
     * @param type       带 {@code @SqlJson} 的实体类，需要无参构造
     * @param dialect    分页方言，null 视为 MySQL
     * @param extraStatements 额外语句工厂，与注解声明合并；与内置同名时覆盖内置，但让位给 resource
     * @return 装配产物
     * @throws SqlSpecException 缺注解、没主键、资源找不到或 JSON 结构错误
     */
    public static Assembled assemble(Class<?> type, Dialect dialect, List<StatementFactory> extraStatements) {
        SqlJson ann = type.getAnnotation(SqlJson.class);
        if (ann == null) {
            throw new SqlSpecException(type.getName()
                    + " 缺少 @SqlJson：SqlJsonRepository 只处理关系型存储，"
                    + "其它存储（Redis / MongoDB）请用对应的仓库实现");
        }
        EntityDescriptor desc = EntityDescriptor.of(type);
        ObjectNode root = loadResource(type, ann.resource());
        mergeDefaults(root, desc, extraStatements);

        SqlV2 engine = SqlV2.parse(root.toString());
        if (desc.softDelete()) {
            String column = desc.deletedColumn();
            engine.rewrite(sql -> SoftDeleteRewriter.filter(sql, column));
        }
        SqlLog.compiled(desc.namespace() + ".*",
                "实体已装配，可用语句 " + engine.ids()
                        + (desc.softDelete() ? "，逻辑删除列=" + desc.deletedColumn() : ""), List.of());

        return new Assembled(desc, engine, dialect == null ? Dialect.mysql() : dialect);
    }

    /** 读资源；没配 resource 时返回空对象，让后续合并从零开始 */
    private static ObjectNode loadResource(Class<?> type, String resource) {
        if (resource == null || resource.isBlank()) {
            return EntityDescriptor.MAPPER.createObjectNode();
        }
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new SqlSpecException("找不到复杂 SQL 资源: " + resource);
            }
            JsonNode node = EntityDescriptor.MAPPER.readTree(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return node.isObject() ? (ObjectNode) node : EntityDescriptor.MAPPER.createObjectNode();
        } catch (IOException e) {
            throw new SqlSpecException("读取复杂 SQL 资源失败: " + resource);
        }
    }

    /**
     * 把默认语句补进资源里已有的命名空间。
     *
     * <p><b>已经存在的不覆盖</b>——这就是「resource 优先」的实现。
     * 顺序上先取已有节点、再逐个 putIfAbsent，保证手写语句不会被注解生成的版本顶掉。
     */
    private static void mergeDefaults(ObjectNode root, EntityDescriptor desc,
                                      List<StatementFactory> extraStatements) {
        ObjectNode ns = root.has(desc.namespace()) && root.get(desc.namespace()).isObject()
                ? (ObjectNode) root.get(desc.namespace())
                : root.putObject(desc.namespace());
        desc.defaultStatements(extraStatements).fields().forEachRemaining(e -> {
            if (!ns.has(e.getKey())) {
                ns.set(e.getKey(), e.getValue());
            }
        });
    }
}
