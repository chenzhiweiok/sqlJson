package io.github.jsonsql.meta.statement;

import io.github.jsonsql.SqlJsonRepository;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.Statements;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认删除语句：{@code DELETE FROM t WHERE id = #1}。
 *
 * <p>注意：实体声明了逻辑删除列时，这条语句仍然生成 DELETE，<b>不会</b>在这里改写成 UPDATE。
 * 改写发生在 {@code SqlJsonRepository.deleteById}：它先检查 {@code desc.softDelete()}，
 * 有逻辑删除列就根本不走这条语句。
 *
 * <p>这样分工的理由：语句是「怎么删」的声明，走哪条路是仓库的决策。
 * 如果把逻辑删除的判断塞进生成器，这条 JSON 就会随实体配置变化，
 * 而 {@code explain()} 里看到的内容也就不可信了。
 */
public final class DeleteByIdStatement implements StatementFactory {

    @Override
    public String name() {
        return "deleteById";
    }

    @Override
    public ObjectNode build(EntityDescriptor d) {
        ObjectNode node = Statements.node();
        node.set("sql", Statements.sql("DELETE FROM " + d.table() + Statements.byId(d)));
        node.put("description", "默认删除");
        return node;
    }
}
