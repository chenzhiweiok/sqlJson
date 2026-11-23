package io.github.jsonsql.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 扫描指定包下所有 {@code @SqlJson} 实体，为每个实体注册一个
 * {@code SqlJsonRepository} Bean，名字为「命名空间 + SqlJsonRepository」。
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableSqlJson(basePackages = "com.demo.relation.entity")
 * public class App { }
 *
 * @Service
 * public class UserService {
 *     private final SqlJsonRepository<SysUser> users;   // 构造函数注入即可
 *     public UserService(SqlJsonRepository<SysUser> users) { this.users = users; }
 * }
 * }</pre>
 *
 * <p>core 模块本身不依赖 Spring；Spring 支持全部集中在本模块，
 * 不用 Spring 的项目引 core 即可，不会被拖进容器生态。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(SqlJsonRegistrar.class)
public @interface EnableSqlJson {

    /** 要扫描的包；留空则扫描被注解类所在包 */
    String[] basePackages() default {};

    /** 容器里的 DataSource Bean 名字 */
    String dataSourceRef() default "dataSource";

    /** 是否把默认 CRUD 语句以外的东西也接进来（目前仅用于日志提示，预留） */
    boolean verbose() default false;
}
