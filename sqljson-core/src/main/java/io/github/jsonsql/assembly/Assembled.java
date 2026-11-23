package io.github.jsonsql.assembly;

import io.github.jsonsql.Dialect;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.support.SqlLog;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 装配产物：一个实体的元数据 + 编译好的语句引擎 + 分页方言。
 *
 * <p>它回答「这个实体有哪些语句、怎么渲染」，<b>不持有连接、不执行</b>。
 * 这条边界是刻意的：装配是启动期一次性工作，执行是请求期反复发生的事，
 * 两者生命周期差了几个数量级，混在一个对象里就会出现「仓库既重又热」的问题。
 *
 * <p>渲染入口也在这里，而不是散在仓库里——因为「语句 id 怎么拼」和「分页怎么翻译」
 * 属于装配的延续。仓库只需要说「我要 {@code findPage}，参数是这些」。
 *
 * @param descriptor 实体元数据
 * @param engine     已编译的语句引擎，含逻辑删除改写器；装配后只读
 * @param dialect    分页方言，永不为 null
 */
public record Assembled(EntityDescriptor descriptor, SqlV2 engine, Dialect dialect) {

    /** 方言兜底：null 视为 MySQL，避免调用方到处判空 */
    public Assembled {
        if (dialect == null) {
            dialect = Dialect.mysql();
        }
    }

    /**
     * 语句全名：{@code namespace.stmt}。
     *
     * @param stmt 短名，如 {@code findPage}
     * @return 引擎里的语句 id
     */
    public String name(String stmt) {
        return descriptor.namespace() + "." + stmt;
    }

    /**
     * 渲染一条语句，并记录编译结果。
     *
     * <p>日志打在这里而不是执行层：SQL 是在编译期定型的，执行期只该补耗时与行数，
     * 否则同一条语句会在日志里出现两遍，量翻倍。
     *
     * @param stmt 短名
     * @param args 参数，可为 null
     * @return 参数化 SQL 与参数
     */
    public SqlV2.Prepared prepare(String stmt, Map<String, Object> args) {
        String id = name(stmt);
        SqlV2.Prepared p = engine.prepare(id, args);
        SqlLog.compiled(id, p.sql(), p.args());
        return p;
    }

    /**
     * 渲染分页语句：列表 SQL 按方言改写，count 不改写。
     *
     * <p>count 是聚合，没有分页子句，翻译过去反而会出错——所以这里只动 list。
     *
     * @param stmt 短名
     * @param args 参数，需含分页参数
     * @return 列表与 count 两条语句及其参数
     */
    public SqlV2.PagePrepared preparePage(String stmt, Map<String, Object> args) {
        String id = name(stmt);
        SqlV2.PagePrepared p = engine.preparePage(id, args);
        String listSql = dialect.rewritePaging(p.listSql());
        SqlV2.PagePrepared out = new SqlV2.PagePrepared(listSql, p.listArgs(),
                p.countSql(), p.countArgs(), p.pageNo(), p.pageSize());
        SqlLog.compiled(id, listSql, p.listArgs());
        SqlLog.compiled(id + ".count", p.countSql(), p.countArgs());
        return out;
    }

    /**
     * 只渲染不记日志，给调试接口用。
     *
     * @param stmt 短名
     * @param args 参数
     * @return {@code sql -- args=[...]} 形式的文本
     */
    public String explain(String stmt, Map<String, Object> args) {
        return engine.prepare(name(stmt), args).toString();
    }

    /**
     * 本实体可用的语句短名，按装配顺序。
     *
     * <p>只返回本命名空间下的语句：引擎里可能同时装着多个实体（桥接场景），
     * 全量返回会让「这个实体到底有哪些语句」变得不可读。
     *
     * @return 短名集合，顺序稳定
     */
    public Set<String> statements() {
        String prefix = descriptor.namespace() + ".";
        Set<String> out = new LinkedHashSet<>();
        for (String id : engine.ids()) {
            if (id.startsWith(prefix)) {
                out.add(id.substring(prefix.length()));
            }
        }
        return out;
    }
}
