package io.github.jsonsql.sql;

import java.util.List;

/**
 * 一条已渲染好的 SQL 与它的参数。
 *
 * <p>存在的理由是让「生成 SQL」和「执行 SQL」能分开：生成方返回这个值对象，
 * 执行方只管绑定与执行，两边都不需要知道对方的细节。
 *
 * <p>参数个数与 {@code ?} 个数必须一致——这是生成方的责任，
 * 执行方不做校验（校验一次要扫全文，而错位会立刻在数据库报错，代价更低）。
 *
 * @param sql  带 {@code ?} 占位符的 SQL
 * @param args 与占位符一一对应的参数，顺序敏感
 */
public record SqlStatement(String sql, List<Object> args) {

    /**
     * 规范化参数：null 视为空列表，避免执行层到处判空。
     *
     * @param sql  SQL
     * @param args 参数，可为 null
     * @return 参数非 null 的语句
     */
    public static SqlStatement of(String sql, List<Object> args) {
        return new SqlStatement(sql, args == null ? List.of() : args);
    }
}
