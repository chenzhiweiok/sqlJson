package com.demo.relation;

import io.github.jsonsql.spring.EnableSqlJson;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户关系系统（Spring Boot 3）。
 *
 * <p>{@code @EnableSqlJson} 扫描 entity 包下所有 {@code @SqlJson} 实体，
 * 为每个实体注册一个 {@code SqlJsonRepository} Bean，
 * 业务层只写「构造函数注入」，不碰 DataSource、不碰编译引擎。
 */
@SpringBootApplication
@EnableSqlJson(basePackages = "com.demo.relation.entity")
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
