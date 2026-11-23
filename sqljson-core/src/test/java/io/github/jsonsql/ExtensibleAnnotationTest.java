package io.github.jsonsql;

import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.EntityParser;
import io.github.jsonsql.meta.EntityParsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「注解解析器可扩展」：新增一个存储注解，不需要改核心代码。
 *
 * <p>这里的 {@code @RedisJson} 只是测试用的最小实现，目的是证明扩展点成立，
 * 不是可用的 Redis 支持——真正的实现还要处理序列化、键拼装、TTL 等。
 */
@DisplayName("注解解析器扩展：新增 @RedisJson 不用改核心")
class ExtensibleAnnotationTest {

    /** 模拟将来要支持的 Redis 注解：容器是键前缀，不是表名 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface RedisJson {
        String value();

        String keyPrefix() default "";
    }

    /** Redis 侧解析器：列名保持 camelCase，不做下划线转换 */
    static class RedisJsonParser implements EntityParser<RedisJson> {

        @Override
        public Class<RedisJson> annotationType() {
            return RedisJson.class;
        }

        @Override
        public EntityDescriptor parse(Class<?> type, RedisJson ann) {
            // 默认键前缀由这个解析器自己决定：Redis 的键习惯保持原样，
            // 不该继承 SQL 那边「容器名 = snake(namespace)」的默认值
            String prefix = ann.keyPrefix().isBlank() ? ann.value() : ann.keyPrefix();
            EntityDescriptor.Builder b = EntityDescriptor.builder(type)
                    .namespace(ann.value())
                    .container(prefix);
            for (Field f : type.getDeclaredFields()) {
                if (f.getName().equals("id")) {
                    b.id(f, f.getName(), false);
                } else {
                    b.column(f, f.getName());
                }
            }
            return b.build();
        }
    }

    @RedisJson(value = "redisUser", keyPrefix = "user:")
    static class RedisUser {
        String id;
        String userName;
    }

    @SqlJson(value = "sqlUser", table = "t_user")
    @RedisJson(value = "redisUser")
    static class BothStorages {
        Long id;
    }

    static class NoStorage {
        Long id;
    }

    @BeforeAll
    static void register() {
        EntityParsers.register(new RedisJsonParser());
    }

    @AfterAll
    static void cleanup() {
        EntityParsers.unregister(RedisJson.class);
    }

    @Test
    @DisplayName("注册后，EntityDescriptor.of 自动认出新注解")
    void resolvesCustomAnnotation() {
        EntityDescriptor d = EntityDescriptor.of(RedisUser.class);
        assertEquals("redisUser", d.namespace());
        assertEquals("user:", d.table(), "容器名来自 keyPrefix，不是表名");
        assertEquals("id", d.idColumn());
        assertFalse(d.softDelete(), "Redis 实体没有逻辑删除列");

        // 列名保持 camelCase：这条区分了「元数据」和「SQL 的命名约定」
        assertTrue(d.allColumnNames().contains("userName"),
                "Redis 侧不该把 userName 转成 user_name: " + d.allColumnNames());
    }

    @Test
    @DisplayName("显式指定解析器：类上有多个注解时绕过歧义")
    void explicitParser() {
        EntityDescriptor d = EntityParsers.parse(BothStorages.class, new RedisJsonParser());
        assertEquals("redisUser", d.namespace());
        assertEquals("redisUser", d.table(), "没有 keyPrefix 时用注解的 value，而不是 SQL 的 snake 默认值");
    }

    @Test
    @DisplayName("同时挂两个存储注解时报错，而不是随便挑一个")
    void ambiguousAnnotation() {
        SqlSpecException e = assertThrows(SqlSpecException.class,
                () -> EntityDescriptor.of(BothStorages.class));
        assertTrue(e.getMessage().contains("多个存储注解"), e.getMessage());
        assertTrue(e.getMessage().contains("@SqlJson") && e.getMessage().contains("@RedisJson"),
                "应把命中的注解都列出来: " + e.getMessage());
    }

    @Test
    @DisplayName("没有注解时，错误消息要列出已注册的注解")
    void missingAnnotation() {
        SqlSpecException e = assertThrows(SqlSpecException.class,
                () -> EntityDescriptor.of(NoStorage.class));
        assertTrue(e.getMessage().contains("@SqlJson"), e.getMessage());
        assertTrue(e.getMessage().contains("EntityParser"), "要提示怎么扩展: " + e.getMessage());
    }

    @Test
    @DisplayName("同一注解重复注册会替换，不会出现两个解析器抢同一个类")
    void reRegisterReplaces() {
        int before = EntityParsers.annotationTypes().size();
        EntityParsers.register(new RedisJsonParser());
        assertEquals(before, EntityParsers.annotationTypes().size(), "同注解类型不应增加条目");
        assertEquals("redisUser", EntityDescriptor.of(RedisUser.class).namespace());
    }

    @Test
    @DisplayName("SQL 仓库仍然只接受 @SqlJson，错误信息要说清原因")
    void sqlRepositoryRejectsOtherStorage() {
        SqlSpecException e = assertThrows(SqlSpecException.class,
                () -> SqlJsonRepository.of(RedisUser.class, null));
        assertTrue(e.getMessage().contains("只处理关系型存储"), e.getMessage());
    }
}
