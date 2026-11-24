package io.github.jsonsql.meta.statement;

import io.github.jsonsql.meta.EntityDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 默认按主键集合查询：{@code SELECT 全列 FROM t WHERE id IN (#{ids}) ORDER BY id ASC}。
 *
 * <p><b>为什么用 {@code #{ids}} 而不是 {@code #1}：</b>编译引擎只对<b>命名占位符</b>里的
 * {@link java.util.Collection} 做展开（一个集合渲染成 {@code ?, ?, ?} 并逐个绑定参数）；
 * 位置参数 {@code #1} 走的是「单个值」路径，塞一个 List 进去会把整个 List 当成一个参数绑给驱动，
 * 得到的是类型错误而不是 IN 列表。这一条差异不写清楚，下一个人一定会踩。
 *
 * <p><b>空集合不在这里处理</b>：引擎遇到空集合会渲染成字面量 {@code null}
 * （{@code IN (null)}），既不报错也查不到东西。仓库层在调用前直接短路返回空列表，
 * 这样连数据库都不用走一趟。这条分工是刻意的——「空输入不必查库」属于编排决策，
 * 不属于 SQL 生成。
 *
 * <p><b>ORDER BY 的意义</b>：没有它，数据库返回顺序不确定；而 {@code findByIds} 的调用方
 * （前端批量渲染、导入校验）通常希望顺序稳定。注意这里按主键排序，不等于「按传入顺序」——
 * 传入顺序由仓库层对结果重排，见 {@code SqlJsonRepository#findByIds}。
 */
public final class FindByIdsStatement implements StatementFactory {

    @Override
    public String name() {
        return "findByIds";
    }

    @Override
    public ObjectNode build(EntityDescriptor d) {
        ObjectNode node = Statements.node();
        node.set("sql", Statements.sql(
                "SELECT " + Statements.selectList(d) + " FROM " + d.table(),
                " WHERE " + d.idColumn() + " IN (#{ids})",
                " ORDER BY " + d.idColumn() + " ASC"));
        node.put("description", "默认按主键集合查询；逻辑删除过滤由改写器追加");
        return node;
    }
}
