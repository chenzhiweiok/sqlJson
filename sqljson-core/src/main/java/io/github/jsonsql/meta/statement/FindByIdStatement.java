package io.github.jsonsql.meta.statement;

import io.github.jsonsql.engine.SoftDeleteRewriter;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.Statements;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认按主键查询：{@code SELECT 全列 FROM t WHERE id = #1}。
 *
 * <p>SELECT 列是全列而不是 {@code *}：{@code *} 会让「实体字段」和「表结构」在运行期
 * 悄悄对齐不上（加了列不映射、删了列报错），而显式列名能让映射问题在装配阶段就暴露。
 *
 * <p>有逻辑删除列时，WHERE 会由 {@link SoftDeleteRewriter} 追加 {@code deleted = 0}，
 * 所以这里不需要也不应该自己写。
 */
public final class FindByIdStatement implements StatementFactory {

    @Override
    public String name() {
        return "findById";
    }

    @Override
    public ObjectNode build(EntityDescriptor d) {
        ObjectNode node = Statements.node();
        node.set("sql", Statements.sql(
                "SELECT " + Statements.selectList(d) + " FROM " + d.table() + Statements.byId(d)));
        node.put("description", "默认按主键查询");
        return node;
    }
}
