/**
 * SQL 文本生成：{@link io.github.jsonsql.sql.CrudSql} 产出批量、软删、恢复、游标等语句；
 * {@link io.github.jsonsql.sql.SqlStatement} 是「SQL + 参数」的载体；
 * {@link io.github.jsonsql.sql.IdSet} 负责主键去重、排序、分块。
 *
 * <p>这一层不执行、不拿连接，所以全部可以无数据库测试。
 */
package io.github.jsonsql.sql;
