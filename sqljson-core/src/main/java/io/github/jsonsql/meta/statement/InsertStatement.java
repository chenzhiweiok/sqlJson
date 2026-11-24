package io.github.jsonsql.meta.statement;

import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.meta.EntityDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认插入语句：{@code INSERT INTO t (#{item}) VALUES (#{item})}。
 *
 * <p>同一个 {@code #{item}} 出现两次、在两个区（列区、值区）展开成两种形态，
 * 这是 {@link SqlV2} 的区域识别在做的事，不是这里写错。
 *
 * <p>{@code columns} 列清单是<b>约束</b>而不是提示：它限定整实体注入时允许写哪些列。
 * 只注入有值的字段（见 {@code expandWholeItem}），所以「只想插三个字段」
 * 不需要另写一条语句，也不需要给其余字段塞默认值。
 */
public final class InsertStatement implements StatementFactory {

    @Override
    public String name() {
        return "insert";
    }

    @Override
    public ObjectNode build(EntityDescriptor d) {
        ObjectNode node = Statements.node();
        node.set("columns", Statements.columns(d.insertColumns()));
        node.set("sql", Statements.sql(
                "INSERT INTO " + d.table() + " (#{item})",
                "VALUES (#{item})"));
        node.put("description", "默认插入：只写有值字段");
        return node;
    }
}
