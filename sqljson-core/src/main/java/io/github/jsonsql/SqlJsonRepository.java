package io.github.jsonsql;

import io.github.jsonsql.assembly.Assembled;
import io.github.jsonsql.assembly.RepositoryAssembler;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.jdbc.CrudSql;
import io.github.jsonsql.jdbc.JdbcExecutor;
import io.github.jsonsql.jdbc.SqlStatement;
import io.github.jsonsql.mapping.BeanMapper;
import io.github.jsonsql.mapping.JoinAssembler;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.statement.StatementFactory;
import io.github.jsonsql.support.SqlMetrics;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 一个实体对应一个仓库：默认 CRUD + 分页由注解生成，复杂 SQL 从资源文件挂载。
 *
 * <p><b>这一层只做编排</b>：把「方法名 + 参数」翻译成「语句名 + 参数」，其余三件事都交出去：
 * <table border="1">
 *   <caption>职责分工</caption>
 *   <tr><th>做什么</th><th>在哪</th></tr>
 *   <tr><td>注解 → 元数据，资源合并，语句编译</td><td>{@link RepositoryAssembler}</td></tr>
 *   <tr><td>语句渲染、分页方言、语句清单</td><td>{@link Assembled}</td></tr>
 *   <tr><td>连接、绑定、计时、日志、指标、映射</td><td>{@link JdbcExecutor}</td></tr>
 *   <tr><td>逻辑删除 / 恢复 / 批量的 SQL 文本</td><td>{@link CrudSql}</td></tr>
 * </table>
 *
 * <p>这样拆的理由不是「文件太大」，而是三者变化频率完全不同：装配只在启动时变，
 * 渲染在改 SQL 时变，执行在换连接池或加埋点时变。混在一起，改任何一处都要重跑全部测试，
 * 也很难单独验证「生成的 SQL 对不对」——现在 {@link CrudSql} 和 {@link Assembled}
 * 都能在没有数据库的情况下测。
 *
 * <p><b>线程安全</b>：装配完成后所有字段只读，可多线程并发使用；
 * 事务状态在 {@link io.github.jsonsql.support.TxContext} 里按线程隔离。
 *
 * @param <T> 实体类型
 */
public final class SqlJsonRepository<T> {

    private final Class<T> type;
    private final EntityDescriptor desc;
    private final Assembled assembled;
    private final JdbcExecutor executor;

    private SqlJsonRepository(Class<T> type, Assembled assembled, JdbcExecutor executor) {
        this.type = type;
        this.desc = assembled.descriptor();
        this.assembled = assembled;
        this.executor = executor;
    }

    /**
     * 装配一个仓库（默认 MySQL 方言）。
     *
     * <p>装配过程中会做四件事：读 {@link SqlJson} 注解 → 反射生成默认 CRUD 的 JSON →
     * 合并 {@code resource} 里的语句 → 装上逻辑删除改写钩子。之后每次查询都只是
     * 渲染 SQL 并执行，不再有反射开销。
     *
     * <p><b>这个方法是重量级的</b>，应在应用启动时调一次并缓存实例（Spring 下就是单例 Bean），
     * 不要放在请求路径里。每次调用都会重新解析资源、重建引擎。
     *
     * @param type       带 {@link SqlJson} 注解的实体类，需要无参构造
     * @param dataSource 数据源，任何 {@code javax.sql.DataSource} 实现都可以
     * @param <T>        实体类型
     * @return 可直接复用的仓库实例；线程安全，可多线程并发使用
     * @throws SqlSpecException 实体没注解、没有主键、默认语句生成失败或资源解析失败
     */
    public static <T> SqlJsonRepository<T> of(Class<T> type, DataSource dataSource) {
        return of(type, dataSource, Dialect.mysql());
    }

    /**
     * 装配一个仓库，并指定分页方言。
     *
     * <p>框架内部永远生成 {@code LIMIT n OFFSET m}，由 {@link Dialect} 在分页时翻译成
     * 目标库语法（Oracle / 达梦走 {@code OFFSET ... ROWS FETCH NEXT ...}）。
     * 只有分页会被改写，其它 SQL 原样执行。
     *
     * @param type       实体类
     * @param dataSource 数据源
     * @param dialect    分页方言；传 null 等同于 {@link Dialect#mysql()}
     * @param <T>        实体类型
     * @return 仓库实例
     * @see #of(Class, DataSource)
     */
    public static <T> SqlJsonRepository<T> of(Class<T> type, DataSource dataSource, Dialect dialect) {
        return of(type, dataSource, dialect, List.of());
    }

    /**
     * 装配仓库，并追加额外的语句模板。
     *
     * <p>与 {@code @SqlJson(statements = ...)} 的区别只是「谁来声明」：
     * 注解适合固定扩展，这个入口适合运行期按配置决定（例如测试、多租户差异化）。
     * 两者同时存在时，参数里的优先——离调用点越近，越应该生效。
     *
     * @param type            实体类
     * @param dataSource      数据源
     * @param dialect         分页方言，可为 null
     * @param extraStatements 额外语句工厂；与 resource 同名时仍让位给 resource
     * @param <T>             实体类型
     * @return 仓库实例
     */
    public static <T> SqlJsonRepository<T> of(Class<T> type, DataSource dataSource, Dialect dialect,
                                              List<StatementFactory> extraStatements) {
        Assembled assembled = RepositoryAssembler.assemble(type, dialect, extraStatements);
        return new SqlJsonRepository<>(type, assembled, new JdbcExecutor(dataSource));
    }

    // ---------------- 事务 ----------------

    /**
     * 在一个事务里执行一段逻辑，抛任何异常都整体回滚。
     *
     * <p>实现要点是「连接放 ThreadLocal，不进参数」：事务块内本仓库的每个方法都会复用
     * 同一个 {@link java.sql.Connection}，所以业务方法不需要传连接，也不需要知道事务存在。
     *
     * <p>嵌套调用时内层用 Savepoint 隔离：内层失败只回滚内层，外层可以选择继续。
     * 跨仓库同样有效（同线程共享连接），因此不需要分布式事务——前提是同一个库。
     *
     * <pre>{@code
     * users.tx(() -> {
     *     users.insert(a);
     *     orders.insert(b);   // 另一个仓库，同一个连接
     * });
     * }</pre>
     *
     * @param body 事务体，不能为 null；抛出 {@link RuntimeException} 即回滚
     */
    public void tx(Runnable body) {
        executor.tx(body);
    }

    /**
     * 带返回值的事务块。
     *
     * <p>与 {@link #tx(Runnable)} 分成两个名字，是因为 lambda 同时能匹配
     * {@code Runnable} 与 {@code Supplier} 时 javac 会报 ambiguous——
     * 调用方写 {@code repo.tx(() -> repo.insert(u))} 时编译器无法判断你要哪个，
     * 只能靠命名区分。这不是设计偏好，是编译期约束。
     *
     * @param body 事务体，返回需要在事务内产出的值
     * @param <R>  返回值类型
     * @return 事务体的返回值
     */
    public <R> R txGet(Supplier<R> body) {
        return executor.txGet(body);
    }

    /**
     * 当前线程是否处于事务中。
     *
     * <p>用于少数需要区分「我是不是被别人包在事务里」的逻辑，例如批量操作时决定
     * 要不要自己提交。业务代码通常不需要关心。
     *
     * @return true 表示当前线程持有事务连接
     */
    public boolean inTransaction() {
        return executor.inTransaction();
    }

    // ---------------- 默认 CRUD ----------------

    /**
     * 插入一条记录。
     *
     * <p>三个行为值得注意：
     * <ul>
     *   <li><b>只写非空字段</b>：实体上为 null 的字段不进 INSERT 列，交给数据库默认值。
     *       所以「只想插两个字段」不需要另写语句。</li>
     *   <li><b>自动填审计字段</b>：createdAt / createdBy 为空时取当前时间与
     *       {@link CurrentUser}，实体上已有值则不覆盖。</li>
     *   <li><b>主键回填</b>：{@code @SqlColumn(generated = true)} 的列不进 SQL，
     *       执行后用 JDBC 返回的主键写回实体，后续可直接拿实体 id 建关联。</li>
     * </ul>
     *
     * @param entity 待插入实体，不能为 null
     * @return 数据库生成的主键；没有自增主键时返回 0
     * @throws SqlSpecException 没有可写入的列，或执行失败
     */
    public long insert(T entity) {
        desc.fillAudit(entity, true);
        SqlV2.Prepared p = assembled.prepare("insert", Map.of("item", entity));

        if (desc.idGenerated()) {
            long id = executor.insertReturningKey(assembled.name("insert"), p.sql(), p.args());
            if (id != 0L) {
                setField(desc.idField(), entity, id);
            }
            return id;
        }
        executor.update(assembled.name("insert"), p.sql(), p.args());
        return idOf(entity);
    }

    /**
     * 按主键更新，<b>只更新非空字段</b>。
     *
     * <p>这是「部分更新」而不是「整行覆盖」：传一个只填了 {@code balance} 的对象，
     * 生成的 SQL 就只有 {@code SET balance = ?}，其余列保持原值。因此：
     * <ul>
     *   <li>适合 patch 语义、前端只提交改动字段的场景；</li>
     *   <li><b>无法用 null 清空某一列</b>——需要清空时写一条显式语句走
     *       {@link #execute(String, List)}，或把该列做成可空语义的专用语句；</li>
     *   <li>主键必须存在于实体上，缺失会直接抛异常而不是全表更新。</li>
     * </ul>
     *
     * <p>有逻辑删除列时，WHERE 会额外带上 {@code deleted = 0}，已删数据不会被复活。
     *
     * @param entity 至少要带主键值的实体
     * @return 受影响行数；0 表示主键不存在或已被逻辑删除
     */
    public int updateById(T entity) {
        desc.fillAudit(entity, false);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("item", entity);
        args.put("id", readId(entity));
        SqlV2.Prepared p = assembled.prepare("updateById", args);
        return executor.update(assembled.name("updateById"), p.sql(), p.args());
    }

    /**
     * 按主键删除。
     *
     * <p>实体声明了逻辑删除列时改写成 {@code update ... set deleted = 1}，
     * 数据仍在表里，可通过 {@link #restoreById(Object)} 恢复、{@link #findDeleted(int)} 查看。
     * 没有逻辑删除列时就是物理 DELETE。
     *
     * <p>这是<b>故意做成同名的</b>：业务代码不该因为「采用了逻辑删除」而改调用点。
     *
     * @param id 主键值
     * @return 受影响行数；0 表示不存在或已删除（重复删除幂等）
     */
    public int deleteById(Object id) {
        if (desc.softDelete()) {
            return executor.update(assembled.name("softDelete"), CrudSql.softDelete(desc, id));
        }
        SqlV2.Prepared p = assembled.prepare("deleteById", Map.of("id", id));
        return executor.update(assembled.name("deleteById"), p.sql(), p.args());
    }

    /**
     * 从回收站恢复一条记录。
     *
     * @param id 主键值
     * @return 受影响行数
     * @throws SqlSpecException 实体没有逻辑删除列
     */
    public int restoreById(Object id) {
        return executor.update(assembled.name("restoreById"), CrudSql.restore(desc, id));
    }

    /**
     * 查已删除的数据，给回收站页面用。
     *
     * @param limit 条数上限，小于 1 按 1 处理
     * @return 每行一个 Map，列名小写；实体没有逻辑删除列时返回空列表
     */
    public List<Map<String, Object>> findDeleted(int limit) {
        if (!desc.softDelete()) {
            return List.of();
        }
        SqlStatement s = CrudSql.findDeleted(desc, limit);
        return executor.queryMaps(assembled.name("findDeleted"), s.sql(), s.args());
    }

    /**
     * 按主键查一条。
     *
     * @param id 主键值
     * @return 实体；<b>找不到时返回 null</b>，不抛异常
     */
    public T findById(Object id) {
        List<T> list = query("findById", Map.of("id", id));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查全表（默认按主键升序）。
     *
     * <p>没有分页、没有条数上限，只适合字典表、下拉选项这类数据量可控的场景。
     *
     * @return 实体列表，无数据时返回空列表
     */
    public List<T> findAll() {
        return query("findAll", Map.of());
    }

    /**
     * 用默认语句分页。
     *
     * <p>会执行两条 SQL：列表页与原样条件的 count。count 从主 SQL 推导，
     * 所以主 SQL 加条件后总数会自动跟着变。
     *
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果，{@code list} 非 null
     */
    public PageResult<T> findPage(int pageNo, int pageSize) {
        return page("findPage", pageNo, pageSize, Map.of());
    }

    // ---------------- 通用 ----------------

    /**
     * 用指定语句分页，返回实体。
     *
     * <p>{@code extra} 是业务参数，与分页参数合并后一起渲染。分页参数本身
     * <b>不进 SQL 占位符</b>：{@code pageNo/pageSize} 由引擎换算成 limit/offset。
     *
     * @param stmt     语句名，不含命名空间
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数
     * @param extra    业务参数，可为空 Map
     * @return 分页结果
     * @throws SqlSpecException 语句不存在、参数缺失或类型不符
     */
    public PageResult<T> page(String stmt, int pageNo, int pageSize, Map<String, Object> extra) {
        SqlV2.PagePrepared p = assembled.preparePage(stmt, with(extra, pageNo, pageSize));
        long total = countOf(stmt, p);
        List<T> list = executor.query(assembled.name(stmt), p.listSql(), p.listArgs(), type);
        return new PageResult<>(list, total, p.pageNo(), p.pageSize());
    }

    /**
     * 用指定语句分页，返回 Map 而不是实体。
     *
     * <p>用于聚合、多表 join、行列不固定的报表类查询。
     *
     * @param stmt     语句名
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @param extra    业务参数
     * @return 每行一个 Map，key 为列名（小写）
     */
    public PageResult<Map<String, Object>> pageMaps(String stmt, int pageNo, int pageSize,
                                                    Map<String, Object> extra) {
        SqlV2.PagePrepared p = assembled.preparePage(stmt, with(extra, pageNo, pageSize));
        long total = countOf(stmt, p);
        List<Map<String, Object>> list = executor.queryMaps(
                assembled.name(stmt), p.listSql(), p.listArgs());
        return new PageResult<>(list, total, p.pageNo(), p.pageSize());
    }

    /**
     * 执行一条查询语句，映射成实体列表。
     *
     * <p>结果列按「列名 → 字段名」匹配，多余的列静默忽略，所以 join 出多余字段也不影响。
     *
     * @param stmt 语句名，不含命名空间
     * @param args 参数
     * @return 实体列表，无数据时返回空列表
     */
    public List<T> query(String stmt, Map<String, Object> args) {
        SqlV2.Prepared p = assembled.prepare(stmt, args);
        return executor.query(assembled.name(stmt), p.sql(), p.args(), type);
    }

    /**
     * 执行一条查询语句，返回 Map 列表。
     *
     * <p>复杂查询（join、聚合、动态列）用这个，再把 Map 交给
     * {@link JoinAssembler} 组两层结构。
     *
     * @param stmt 语句名
     * @param args 参数
     * @return 每行一个 Map，key 为列名（小写）
     */
    public List<Map<String, Object>> queryMaps(String stmt, Map<String, Object> args) {
        SqlV2.Prepared p = assembled.prepare(stmt, args);
        return executor.queryMaps(assembled.name(stmt), p.sql(), p.args());
    }

    /**
     * 调试：看某条语句编译出来的样子（不执行、不记执行日志）。
     *
     * @param stmt 语句名
     * @param args 参数
     * @return {@code sql -- args=[...]} 形式的文本
     */
    public String explain(String stmt, Map<String, Object> args) {
        return assembled.explain(stmt, args);
    }

    /**
     * 本仓库编译引擎，供 MyBatis 桥接等场景复用。
     *
     * @return 引擎实例；装配后只读
     */
    public SqlV2 engine() {
        return assembled.engine();
    }

    /**
     * 语句级指标（全局单例）。
     *
     * @return 指标收集器
     */
    public SqlMetrics metrics() {
        return SqlMetrics.global();
    }

    /**
     * 调试：看分页语句编译出来是什么样（含方言改写）。
     *
     * @param stmt 语句名
     * @param args 参数
     * @return 列表 SQL 与参数
     */
    public String explainPage(String stmt, Map<String, Object> args) {
        SqlV2.PagePrepared p = assembled.preparePage(stmt, args);
        return p.listSql() + "  -- args=" + p.listArgs();
    }

    /**
     * 当前实体可用的语句名（去掉命名空间前缀）。
     *
     * <p>排查「这条语句到底装配进来没有」时最有用。
     *
     * @return 语句名集合，顺序与装配顺序一致
     */
    public java.util.Set<String> statements() {
        return assembled.statements();
    }

    /**
     * 执行任意更新 SQL（不在 JSON 里的临时语句）。
     *
     * <p>只接受参数化 SQL；把值拼进字符串会绕过全部防护。
     *
     * @param sql  带 {@code ?} 的 SQL
     * @param args 参数，可为 null
     * @return 受影响行数
     */
    public int execute(String sql, List<Object> args) {
        return executor.update("raw", sql, args == null ? List.of() : args);
    }

    // ---------------- 批量 ----------------

    /**
     * 批量插入：拼多行 VALUES，按 500 行一批。
     *
     * <p>只写「在任一实体里出现过值」的列，没传的列交给数据库默认值。
     * 分块之间<b>不是原子的</b>：第 3 块失败时前两块已提交，除非外层用 {@link #tx(Runnable)} 包起来。
     *
     * @param list 待插入实体
     * @return 总影响行数
     * @throws SqlSpecException 没有任何可写入的列
     */
    public int insertBatch(List<T> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        for (T e : list) {
            desc.fillAudit(e, true);
        }
        int total = 0;
        for (SqlStatement s : CrudSql.insertBatch(desc, list, 500)) {
            total += executor.update(assembled.name("insertBatch"), s);
        }
        return total;
    }

    /**
     * 批量更新：一条 UPDATE + CASE WHEN，一次往返。
     *
     * <p>只更新「在任一实体里出现过值」的列；主键为 0 的实体会被跳过；
     * 有逻辑删除列时自动过滤已删数据。
     *
     * @param list 待更新实体
     * @return 受影响行数
     */
    public int updateBatch(List<T> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        List<T> valid = new ArrayList<>();
        for (T e : list) {
            if (idOf(e) != 0) {
                valid.add(e);
            }
        }
        if (valid.isEmpty()) {
            return 0;
        }
        for (T e : valid) {
            desc.fillAudit(e, false);
        }
        SqlStatement s = CrudSql.updateBatch(desc, valid);
        return s == null ? 0 : executor.update(assembled.name("updateBatch"), s);
    }

    // ---------------- 内部 ----------------

    /** count 走独立语句名，指标里才能区分「列表慢」和「count 慢」 */
    private long countOf(String stmt, SqlV2.PagePrepared p) {
        return executor.count(assembled.name(stmt) + ".count", p.countSql(), p.countArgs());
    }

    /** 分页参数不进 SQL，所以只能走 Map：引擎只认 args 里的键 */
    private Map<String, Object> with(Map<String, Object> extra, int pageNo, int pageSize) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (extra != null) {
            args.putAll(extra);
        }
        args.put("pageNo", pageNo);
        args.put("pageSize", pageSize);
        args.put("offset", (pageNo - 1) * pageSize);
        return args;
    }

    private long idOf(T entity) {
        try {
            Object id = desc.idField().get(entity);
            return id instanceof Number n ? n.longValue() : 0L;
        } catch (IllegalAccessException e) {
            return 0L;
        }
    }

    private Object readId(T entity) {
        try {
            Object id = desc.idField().get(entity);
            if (id == null) {
                throw new SqlSpecException("实体主键为 null，无法按主键操作");
            }
            return id;
        } catch (IllegalAccessException e) {
            throw new SqlSpecException("读取主键失败");
        }
    }

    /** 类型转换交给 {@link BeanMapper#convert}，避免这里再维护一份（原先两份，早晚不一致） */
    private static void setField(Field field, Object bean, Object value) {
        try {
            field.setAccessible(true);
            field.set(bean, BeanMapper.convert(value, field.getType()));
        } catch (IllegalAccessException e) {
            throw new SqlSpecException("写入字段 " + field.getName() + " 失败");
        }
    }
}
