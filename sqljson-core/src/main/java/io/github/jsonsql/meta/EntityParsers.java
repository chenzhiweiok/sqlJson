package io.github.jsonsql.meta;

import io.github.jsonsql.SqlSpecException;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 注解解析器注册表：决定「一个类上的注解由谁来读」。
 *
 * <p>默认只注册了 SQL 的 {@link SqlJsonParser}，行为与以前完全一致——
 * 加这层抽象的目的是让 {@code @RedisJson} / {@code @MongoDbJson} 这类新注解
 * 通过 {@link #register(EntityParser)} 接入，而不是去改核心类。
 *
 * <h2>为什么是显式注册，而不是自动扫描</h2>
 * 自动扫描（classpath 扫描或 ServiceLoader）看着省事，但它有一个很坏的失败模式：
 * <b>静默失效</b>。类路径不对、{@code META-INF/services} 文件少了、构造器不是 public，
 * 任何一处错都表现为「注解没生效」，而不是报错。显式注册至少能在代码里搜到调用点。
 *
 * <p>代价是启动时要有一行注册代码。对于「换存储后端」这种低频决策，这个代价可以接受。
 *
 * <h2>什么时候适合再注册一个解析器</h2>
 * <ul>
 *   <li>新的注解语义（{@code @RedisJson} 的 key 前缀 vs {@code @SqlJson} 的表名）；</li>
 *   <li>同一份元数据但命名策略不同（Mongo 通常保留 camelCase，SQL 转 snake_case）；</li>
 *   <li>测试里替换默认解析器，构造特殊元数据。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：注册用写时复制列表，读多写少。约定在启动期注册完毕，
 * 运行期不再增删——并发注册虽然不会抛异常，但「某个请求用了新解析器、另一个没有」
 * 会让排查变得毫无线索。
 */
public final class EntityParsers {

    private static final List<EntityParser<?>> PARSERS = new CopyOnWriteArrayList<>();

    static {
        register(new SqlJsonParser());
    }

    private EntityParsers() {
    }

    /**
     * 注册一个解析器。
     *
     * <p>同一个注解类型再次注册会<b>替换</b>旧的，而不是追加。这样做的用途很明确：
     * 测试里覆盖默认实现、或同一个注解在不同环境用不同策略。
     * 替换是静默的——如果担心误覆盖，可以在注册前用 {@link #annotationTypes()} 检查。
     *
     * @param parser 解析器，不能为 null
     * @throws SqlSpecException 传入 null
     */
    public static void register(EntityParser<?> parser) {
        if (parser == null || parser.annotationType() == null) {
            throw new SqlSpecException("EntityParser 不能为 null，且 annotationType() 不能返回 null");
        }
        PARSERS.removeIf(p -> p.annotationType().equals(parser.annotationType()));
        PARSERS.add(parser);
    }

    /**
     * 注销某个注解的解析器，主要给测试清理用。
     *
     * <p>生产代码不该调用：解析器少一个，对应的实体就装配不起来，
     * 而这种失败发生在第一次使用该实体时，不是启动时。
     *
     * @param annotationType 注解类型
     * @return true 表示确实移除了一个
     */
    public static boolean unregister(Class<? extends Annotation> annotationType) {
        return PARSERS.removeIf(p -> p.annotationType().equals(annotationType));
    }

    /**
     * 已注册的注解类型，按注册顺序。
     *
     * <p>两个用途：启动日志里打一遍（「这个服务认识哪些存储注解」），
     * 以及解析失败时把清单放进异常消息——比一句「缺少注解」有用得多。
     *
     * @return 只读快照
     */
    public static List<Class<? extends Annotation>> annotationTypes() {
        List<Class<? extends Annotation>> out = new ArrayList<>(PARSERS.size());
        for (EntityParser<?> p : PARSERS) {
            out.add(p.annotationType());
        }
        return List.copyOf(out);
    }

    /**
     * 按类上的注解找到解析器并解析。
     *
     * <p>这是 {@link EntityDescriptor#of(Class)} 的实现，也是业务代码的默认入口。
     *
     * @param type 实体类
     * @return 实体元数据
     * @throws SqlSpecException 没有匹配的注解、多个注解同时存在、或元数据不完整
     */
    public static EntityDescriptor parse(Class<?> type) {
        return parse(type, resolve(type));
    }

    /**
     * 用指定解析器解析，跳过查找。
     *
     * <p>用于「一个类上既有 {@code @SqlJson} 又有别的注解」的场景，
     * 或者测试里显式指定策略。此时不再检查注解是否存在，由解析器自己决定。
     *
     * @param type   实体类
     * @param parser 解析器
     * @param <A>    注解类型
     * @return 实体元数据
     * @throws SqlSpecException 类上没有该解析器要求的注解，或元数据不完整
     */
    public static <A extends Annotation> EntityDescriptor parse(Class<?> type, EntityParser<A> parser) {
        A annotation = type.getAnnotation(parser.annotationType());
        if (annotation == null) {
            throw new SqlSpecException(type.getName() + " 缺少 @" + parser.annotationType().getSimpleName() + " 注解");
        }
        return parser.parse(type, annotation);
    }

    /**
     * 找出这个类该用哪个解析器。
     *
     * <p>命中多个时直接报错，而不是「取第一个」。两个存储注解同时挂在一个类上，
     * 说明设计有问题（同一份字段要同时映射到两种存储），静默选一个会让另一半
     * 永远不生效，而且看不出来。
     *
     * @param type 实体类
     * @return 唯一命中的解析器
     * @throws SqlSpecException 没有命中或多于一个
     */
    static EntityParser<?> resolve(Class<?> type) {
        List<EntityParser<?>> matched = new ArrayList<>(1);
        for (EntityParser<?> p : PARSERS) {
            if (type.isAnnotationPresent(p.annotationType())) {
                matched.add(p);
            }
        }
        if (matched.size() == 1) {
            return matched.get(0);
        }
        if (matched.isEmpty()) {
            throw new SqlSpecException(type.getName() + " 上没有可识别的存储注解，已注册: "
                    + names() + "。自定义注解请实现 EntityParser 并调用 EntityParsers.register()");
        }
        List<String> hit = new ArrayList<>(matched.size());
        for (EntityParser<?> p : matched) {
            hit.add("@" + p.annotationType().getSimpleName());
        }
        throw new SqlSpecException(type.getName() + " 同时存在多个存储注解 " + hit
                + "，无法判断用哪个；请只保留一个，或用 EntityParsers.parse(type, parser) 显式指定");
    }

    /** 注解名清单，只给错误消息与日志用 */
    private static String names() {
        List<String> out = new ArrayList<>(PARSERS.size());
        for (EntityParser<?> p : PARSERS) {
            out.add("@" + p.annotationType().getSimpleName());
        }
        return String.join(", ", out);
    }
}
