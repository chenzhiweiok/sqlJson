package io.github.jsonsql;
import io.github.jsonsql.meta.statement.StatementFactory;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 打在实体类上，这个实体就获得一套默认 CRUD、分页与资源语句能力。
 *
 * <p>注解不是运行时反射查一次就完，而是在装配阶段<b>生成一份 v2 格式的 JSON</b>，
 * 再交给与手写 JSON 完全相同的编译引擎。这样两条路径的行为不会分叉：
 * 占位符规则、ident 白名单、逻辑删除改写、分页推导都只有一套实现。
 *
 * <p>生成的默认语句（每次装配都重新生成，不落盘）：
 * <table border="1">
 *   <caption>默认语句</caption>
 *   <tr><th>语句</th><th>SQL 形态</th></tr>
 *   <tr><td>{@code insert}</td><td>{@code INSERT INTO t (非空列) VALUES (?, ...)}</td></tr>
 *   <tr><td>{@code updateById}</td><td>{@code UPDATE t SET 非空列 = ? WHERE id = ?}</td></tr>
 *   <tr><td>{@code deleteById}</td><td>{@code DELETE FROM t WHERE id = ?}（有逻辑删除列时改写为 update）</td></tr>
 *   <tr><td>{@code findById}</td><td>{@code SELECT 全列 FROM t WHERE id = ?}</td></tr>
 *   <tr><td>{@code findAll}</td><td>{@code SELECT 全列 FROM t ORDER BY id}</td></tr>
 *   <tr><td>{@code findPage}</td><td>{@code SELECT 全列 FROM t ORDER BY id} + 标配分页</td></tr>
 * </table>
 *
 * <p>示例：
 * <pre>{@code
 * @SqlJson(value = "user", table = "sys_user", resource = "sqls/sys_user.json")
 * public class SysUser {
 *     @SqlColumn(id = true, generated = true) Long id;
 *     @SqlColumn("user_name") String userName;
 * }
 *
 * SqlJsonRepository<SysUser> repo = SqlJsonRepository.of(SysUser.class, dataSource);
 * repo.findAll();   // 用的是上面生成的 findAll
 * }</pre>
 *
 * <p><b>与 resource 的关系</b>：资源里的同名语句优先。想给默认分页加筛选条件，
 * 在 JSON 里重写 {@code findPage} 即可，不需要改注解，也不需要改 Java 代码。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SqlJson {

    /**
     * 命名空间，同时是语句 id 的前缀：{@code user.insert}、{@code user.findPage}。
     *
     * <p>同一进程里应保持唯一；重名会导致后装配的实体覆盖前一个的语句。
     * 用 Spring 时它还会参与 Bean 命名（{@code userSqlJsonRepository}）。
     *
     * @return 命名空间
     */
    String value();

    /**
     * 物理表名。
     *
     * @return 留空时按命名空间转下划线推导（{@code userProfile → user_profile}）
     */
    String table() default "";

    /**
     * 复杂 SQL 的资源路径，走 classpath，例如 {@code sqls/user.json}。
     *
     * <p>文件里的同名语句覆盖默认 CRUD；其余语句追加到可用语句集合。
     * 留空表示只用默认 CRUD。
     *
     * @return classpath 路径
     */
    String resource() default "";

    /**
     * 未在任何字段上标 {@code id = true} 时，用这个列名当主键。
     *
     * @return 主键列名，默认 {@code id}
     */
    String idColumn() default "id";

    /**
     * 在默认六条语句之外，追加或覆盖语句模板。
     *
     * <p>这是「模板扩展」的入口：实现 {@link StatementFactory}，在这里声明，
     * 这个实体就多一条（或改一条）默认语句，不需要写 JSON，也不需要改框架。
     *
     * <pre>{@code
     * @SqlJson(value = "user", table = "t_user",
     *          statements = { CountAllStatement.class, MyFindPage.class })
     * }</pre>
     *
     * <p><b>覆盖规则</b>：与内置语句同名时替换其内容，但保留原有位置——
     * 顺序稳定，日志和 {@code statements()} 的输出才对得上。
     *
     * <p><b>优先级</b>：{@code resource} 里的同名语句仍然最高。
     * 理由很实际：JSON 是可以改配置、走发布流程的，注解要重新编译；
     * 临时改一条 SQL 不该逼人发版。
     *
     * <p>类必须有无参构造，且是 {@code public}——反射在装配阶段只做一次，
     * 失败会直接抛 {@code SqlSpecException}，不会拖到第一次查询。
     *
     * @return 额外的语句工厂，默认空
     */
    Class<? extends StatementFactory>[] statements() default {};
}
