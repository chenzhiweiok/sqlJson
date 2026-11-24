/**
 * 执行层：{@link io.github.jsonsql.jdbc.JdbcExecutor} 是唯一接触 {@code java.sql} 的类，
 * 负责连接、参数绑定、计时、日志、指标、结果映射与流式读取。
 *
 * <p>它不知道实体、注解、SQL 从哪来；输入是 {@code sql + args}。
 */
package io.github.jsonsql.jdbc;
