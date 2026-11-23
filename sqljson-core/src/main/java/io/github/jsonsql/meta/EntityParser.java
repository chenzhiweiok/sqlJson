package io.github.jsonsql.meta;

import java.lang.annotation.Annotation;

/**
 * 注解 → 实体元数据的解析器。
 *
 * <p>这个接口是「注解可扩展」的落点：核心只认 {@link EntityDescriptor} 这一份元数据，
 * 至于元数据从哪个注解来，由实现决定。所以加 {@code @RedisJson} / {@code @MongoDbJson}
 * 不需要改 {@code EntityDescriptor}，也不再复制一遍字段反射逻辑。
 *
 * <pre>{@code
 * public final class RedisJsonParser implements EntityParser<RedisJson> {
 *     public Class<RedisJson> annotationType() { return RedisJson.class; }
 *
 *     public EntityDescriptor parse(Class<?> type, RedisJson ann) {
 *         EntityDescriptor.Builder b = EntityDescriptor.builder(type)
 *                 .namespace(ann.value())
 *                 .container(ann.keyPrefix());          // 键前缀，语义等同表名
 *         for (Field f : type.getDeclaredFields()) {
 *             b.column(f, f.getName());                 // Redis 不做下划线转换
 *         }
 *         return b.build();
 *     }
 * }
 * }</pre>
 *
 * <p><b>实现方必须无状态</b>：解析器会被所有实体共用，注入到注册表里长期存活；
 * 把状态挂在字段上会在并发装配时互相污染。装配通常在启动期单线程完成，
 * 但这不是可以依赖的前提。
 *
 * <p><b>不要在这里访问数据库</b>：解析只做反射与注解读取。连接、探测、建表
 * 都属于仓库的职责，混进来会让「启动时解析元数据」变成「启动时连一圈库」。
 *
 * @param <A> 该解析器识别的注解类型
 * @see EntityParsers
 * @see SqlJsonParser
 */
public interface EntityParser<A extends Annotation> {

    /**
     * 本解析器识别的注解。
     *
     * <p>注册表用它判断「这个类归不归我解析」，也是重复注册时的去重键。
     * 同一个注解类型只能有一个生效的解析器。
     *
     * @return 注解类型，不能为 null
     */
    Class<A> annotationType();

    /**
     * 解析一个实体类。
     *
     * <p>调用前提是类上确实存在 {@link #annotationType()} 标明的注解——
     * 注册表已经判断过，实现里不需要再判空。
     *
     * <p>必须完成三件事，否则下游会以各种方式失败：
     * <ul>
     *   <li>{@code namespace}：语句 id 的前缀，也用于日志定位；</li>
     *   <li>{@code container}：表名 / 集合名 / 键前缀，SQL 与文档存储都靠它定位数据；</li>
     *   <li>至少一个主键列：没有主键就生成不出 byId 类语句。</li>
     * </ul>
     *
     * @param type       被解析的实体类，带注解，未做其它校验
     * @param annotation 已从类上取到的注解实例
     * @return 不可变的实体元数据
     * @throws io.github.jsonsql.SqlSpecException 元数据不完整（缺 namespace、缺主键等）
     */
    EntityDescriptor parse(Class<?> type, A annotation);
}
