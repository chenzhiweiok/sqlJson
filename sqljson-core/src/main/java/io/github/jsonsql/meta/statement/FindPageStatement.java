package io.github.jsonsql.meta.statement;

import io.github.jsonsql.Dialect;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.Statements;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认分页查询：{@code SELECT 全列 FROM t ORDER BY id ASC} + 分页声明。
 *
 * <p>SQL 里<b>不写 LIMIT</b>：分页子句由引擎在渲染后追加，并按 {@link Dialect} 翻译成
 * 目标库语法。自己写死 LIMIT 会让 Oracle / 达梦直接用不了，而且 count 推导也会失去锚点。
 *
 * <p>{@code style = page} 表示调用方传 {@code pageNo + pageSize}；
 * 换成 {@code offset} 则传 {@code offset + pageSize}。默认给 page，
 * 因为前端分页器天然是「第几页」。
 *
 * <p>count 语句不在这里生成——引擎会把主 SQL 的 select 列表换成 {@code COUNT(*)}、
 * 去掉 ORDER BY 推导出来。所以改条件时只需要改一处，总数不会跟着错。
 */
public final class FindPageStatement implements StatementFactory {

    @Override
    public String name() {
        return "findPage";
    }

    @Override
    public ObjectNode build(EntityDescriptor d) {
        ObjectNode node = Statements.node();
        node.set("sql", Statements.sql(
                "SELECT " + Statements.selectList(d),
                "  FROM " + d.table(),
                " ORDER BY " + d.idColumn() + " ASC"));

        ObjectNode pagination = node.putObject("pagination");
        pagination.put("style", "page");
        pagination.put("pageParam", "pageNo");
        pagination.put("sizeParam", "pageSize");

        node.put("description", "默认分页：全表按主键排序，复杂条件请覆盖此语句");
        return node;
    }
}
