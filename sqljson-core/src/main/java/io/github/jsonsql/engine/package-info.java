/**
 * 编译引擎：{@link io.github.jsonsql.engine.SqlV2} 把 JSON 渲染成参数化 SQL 与参数；
 * {@link io.github.jsonsql.engine.SoftDeleteRewriter} 在渲染后补逻辑删除条件。
 *
 * <p>不依赖 JDBC：这一层只回答「SQL 长什么样」，不回答「怎么执行」。
 */
package io.github.jsonsql.engine;
