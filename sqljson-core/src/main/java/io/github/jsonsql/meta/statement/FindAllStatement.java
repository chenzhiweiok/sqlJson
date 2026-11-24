package io.github.jsonsql.meta.statement;

import io.github.jsonsql.meta.EntityDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认全量查询：{@code SELECT 全列 FROM t ORDER BY id ASC}。
 *
 * <p>没有分页、没有条数上限。它存在是为了「字典表、下拉选项」这类小表，
 * 不是给业务表用的——所以 description 里写明了界限，而不是默默让它能查百万行。
 *
 * <p>按主键排序是刻意的：不排序时数据库返回顺序不确定，前端下拉每次刷新顺序都在变，
 * 用户会以为数据在乱跳。
 *
 * <p>业务表要查全部时，用 {@code findPage}，或者覆盖这条语句加上自己的过滤条件
 * （{@code resource} 里的同名语句优先）。
 */
public final class FindAllStatement implements StatementFactory {

    @Override
    public String name() {
        return "findAll";
    }

    @Override
    public ObjectNode build(EntityDescriptor d) {
        ObjectNode node = Statements.node();
        node.set("sql", Statements.sql("SELECT " + Statements.selectList(d)
                + " FROM " + d.table() + " ORDER BY " + d.idColumn() + " ASC"));
        node.put("description", "默认全量查询");
        return node;
    }
}
