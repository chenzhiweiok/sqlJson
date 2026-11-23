package com.demo.relation.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记只有管理员能调用的接口。
 *
 * <p>用注解而不是 Spring Security，是因为这个 demo 的目的是展示 sqljson，
 * 而不是展示安全框架。真上生产要换成 Security 或至少引入 BCrypt + CSRF 防护。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
}
