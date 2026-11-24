package io.github.jsonsql.meta.statement;

import io.github.jsonsql.SqlSpecException;
import io.github.jsonsql.meta.EntityDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认语句集合：按固定顺序装配六条策略，产出一个命名空间节点。
 *
 * <p>顺序有意义——{@code statements()} 和启动日志都按这个顺序输出，
 * 「哪条语句先进来」在排查覆盖问题时能对上号。
 *
 * <p>重名会抛异常而不是静默覆盖：六条默认语句的 id 是业务代码直接引用的
 * （{@code repo.findById()} 之类），安静地少一条，报错会跑到很远的地方。
 *
 * <p>要加第七条，两步：写一个 {@link StatementFactory}，加进 {@link #STANDARD}。
 * 不需要动 {@link EntityDescriptor}，也不需要动仓库——这是拆开之后唯一的好处，
 * 也是拆开的全部理由。
 */
public final class DefaultStatementSet {

    /** 默认七条，顺序即输出顺序；要加第八条只需写一个 StatementFactory 并加进来 */
    private static final List<StatementFactory> STANDARD = List.of(
            new InsertStatement(),
            new UpdateByIdStatement(),
            new DeleteByIdStatement(),
            new FindByIdStatement(),
            new FindByIdsStatement(),
            new FindAllStatement(),
            new FindPageStatement());

    private DefaultStatementSet() {
    }

    /**
     * 用默认集合生成。
     *
     * @param entity 实体元数据
     * @return 命名空间节点，key 为语句名
     */
    public static ObjectNode build(EntityDescriptor entity) {
        return build(entity, STANDARD);
    }

    /**
     * 用指定集合生成。
     *
     * <p>允许注入是为了测试：验证「某条语句生成得对不对」不必经过 {@code EntityDescriptor}
     * 的整套反射，直接 new 一个语句类，喂一个手工构造的描述符即可。
     *
     * @param entity    实体元数据
     * @param factories 语句策略，不能有重名；空集合返回空节点
     * @return 命名空间节点
     * @throws SqlSpecException 语句名重复，或某条语句生成失败
     */
    public static ObjectNode build(EntityDescriptor entity, List<StatementFactory> factories) {
        ObjectNode ns = EntityDescriptor.MAPPER.createObjectNode();
        Map<String, String> seen = new LinkedHashMap<>();
        for (StatementFactory factory : factories) {
            String name = factory.name();
            String previous = seen.put(name, factory.getClass().getSimpleName());
            if (previous != null) {
                throw new SqlSpecException(entity.namespace() + ": 默认语句名重复 " + name
                        + "（" + previous + " 与 " + factory.getClass().getSimpleName() + "）");
            }
            ns.set(name, factory.build(entity));
        }
        return ns;
    }

    /**
     * 内置六条 + 扩展，同名覆盖。
     *
     * <p>覆盖时<b>保留原位置</b>（{@link LinkedHashMap} 的 put 语义），
     * 而不是把被覆盖的语句挪到末尾。顺序稳定是刻意的：{@code statements()}
     * 和启动日志都按顺序输出，位置一变，「我加的那条到底进没进去」就难对照了。
     *
     * @param entity     实体元数据
     * @param extensions 扩展语句；与内置同名则覆盖，未同名则追加
     * @return 命名空间节点
     */
    public static ObjectNode buildWith(EntityDescriptor entity, List<StatementFactory> extensions) {
        Map<String, StatementFactory> merged = new LinkedHashMap<>();
        for (StatementFactory f : STANDARD) {
            merged.put(f.name(), f);
        }
        if (extensions != null) {
            for (StatementFactory f : extensions) {
                if (f == null) {
                    throw new SqlSpecException(entity.namespace() + ": 扩展语句里有 null");
                }
                merged.put(f.name(), f);
            }
        }
        return build(entity, new ArrayList<>(merged.values()));
    }

    /**
     * 默认语句名，按输出顺序。
     *
     * <p>用于日志与 {@code statements()} 的对照，避免「明明声明了六条，怎么只看到五条」。
     *
     * @return 语句名列表，不可修改
     */
    public static List<String> names() {
        List<String> out = new ArrayList<>(STANDARD.size());
        for (StatementFactory f : STANDARD) {
            out.add(f.name());
        }
        return List.copyOf(out);
    }
}
