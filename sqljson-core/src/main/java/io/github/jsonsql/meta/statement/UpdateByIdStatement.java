package io.github.jsonsql.meta.statement;

import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.Statements;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认更新语句：{@code UPDATE t SET #{item} WHERE id = #1}。
 *
 * <p>SET 区同样由 {@code #{item}} 展开，且只展开有值的字段——这就是「部分更新」的来源。
 * 代价是<b>无法用 null 清空一列</b>（null 被视为「不更新」），要清空得写显式语句。
 *
 * <p>{@code updateColumns()} 排除了主键与逻辑删除列：主键进 WHERE，逻辑删除列由
 * {@code softDelete} / {@code restore} 专门处理，混进 SET 会让「恢复」变成一次普通更新。
 */
public final class UpdateByIdStatement implements StatementFactory {

    @Override
    public String name() {
        return "updateById";
    }

    @Override
    public ObjectNode build(EntityDescriptor d) {
        ObjectNode node = Statements.node();
        node.set("columns", Statements.columns(d.updateColumns()));
        node.set("sql", Statements.sql(
                "UPDATE " + d.table() + " SET #{item}",
                Statements.byId(d)));
        node.put("description", "默认更新：只更新有值字段");
        return node;
    }
}
