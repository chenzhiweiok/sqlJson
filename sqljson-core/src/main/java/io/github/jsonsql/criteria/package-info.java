/**
 * 运行期条件：{@link io.github.jsonsql.criteria.Criteria} 产出参数化的 where 片段，
 * 供计数、存在性判断、条件更新共用。
 *
 * <p>只支持 AND 组合。需要 OR / 子查询 / JOIN 时写 JSON 语句——条件构建器不该长成表达式树。
 */
package io.github.jsonsql.criteria;
