package io.github.jsonsql;

import io.github.jsonsql.assembly.Assembled;
import io.github.jsonsql.assembly.RepositoryAssembler;
import io.github.jsonsql.criteria.Criteria;
import io.github.jsonsql.engine.SqlV2;
import io.github.jsonsql.sql.CrudSql;
import io.github.jsonsql.jdbc.JdbcExecutor;
import io.github.jsonsql.sql.SqlStatement;
import io.github.jsonsql.mapping.BeanMapper;
import io.github.jsonsql.mapping.JoinAssembler;
import io.github.jsonsql.meta.EntityDescriptor;
import io.github.jsonsql.meta.statement.StatementFactory;
import io.github.jsonsql.sql.IdSet;
import io.github.jsonsql.support.SqlMetrics;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        // CrudSql 生成的是 MySQL 写法，Oracle / 达梦必须经方言翻译，否则直接语法错
        String sql = assembled.dialect().rewritePaging(s.sql());
        return executor.queryMaps(assembled.name("findDeleted"), sql, s.args());
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

    // ---------------- 条件统计 ----------------

    /**
     * 按字段等值计数。
     *
     * <p>等价于 {@code countBy(Criteria.of().eq(field, value))}，用于最常用的
     * 「这个状态有多少条」——例如导入前去重、或给前端展示各状态的数量。
     *
     * @param field 实体字段名（{@code userName}），也接受列名（{@code user_name}）
     * @param value 比较值；<b>传 null 表示 {@code is null}</b>，不会报错
     * @return 满足条件的行数，不含已逻辑删除的数据
     * @throws SqlSpecException 字段不存在于实体
     */
    public long countBy(String field, Object value) {
        return countBy(Criteria.of().eq(field, value));
    }

    /**
     * 按条件计数。
     *
     * <p>走「直接生成 SQL」的路径而不是 JSON 语句：条件由调用方在运行期决定，
     * 静态语句表达不了。字段名经实体元数据解析成列名并校验，值全部参数化，
     * 所以拼进 SQL 的只有可信的列名。
     *
     * <p>三点行为与 {@link #findPage(int, int)} 的 count 保持一致：
     * <ul>
     *   <li>实体声明了逻辑删除列时<b>自动排除已删数据</b>，无需调用方关心；</li>
     *   <li>始终执行一条 {@code select count(*)}，不做结果缓存——数据一变缓存就成了错的；</li>
     *   <li>指标里以独立语句名 {@code countBy} 记录，能和分页的 {@code findPage.count} 区分开。</li>
     * </ul>
     *
     * <p><b>性能提示</b>：大表上 {@code count(*)} 需要扫完整个匹配集（走覆盖索引也仍是 O(n)）。
     * 只关心「有没有」时用 {@code existsBy}，它读到第一行就停；需要精确总数时，
     * 确保 where 列上有索引。
     *
     * @param criteria 条件；null 等同于无条件（即全表计数）
     * @return 满足条件的行数
     */
    public long countBy(Criteria criteria) {
        Criteria.Rendered r = (criteria == null ? Criteria.of() : criteria).render(desc);
        String sql = "select count(*) from " + desc.table() + r.where();
        return executor.count(assembled.name("countBy"), sql, r.args());
    }

    /**
     * 全表计数（不含已逻辑删除的数据）。
     *
     * <p>单独给一个名字而不是要求调用方写 {@code countBy(Criteria.of())}：
     * 「总数」在分页器、看板上是高频调用，读起来应该和它的语义一样直接。
     *
     * @return 行数
     */
    public long countAll() {
        return countBy(Criteria.of());
    }

    /**
     * 是否存在满足条件的行。
     *
     * <p>等价于 {@code countBy(criteria) > 0}，但<b>成本完全不同</b>：
     * 生成的是 {@code select 1 ... limit 1}，匹配到第一行就停；
     * {@code count(*)} 则要扫完整个匹配集。导入前查重、手机号是否已注册，都应该用这个。
     *
     * <p>{@code limit 1} 会经方言翻译（Oracle / 达梦走 {@code fetch first 1 rows only}），
     * 不是硬拼进去的。
     *
     * @param criteria 条件；null 等同于「表里有没有数据」
     * @return true 表示至少有一行
     */
    public boolean existsBy(Criteria criteria) {
        Criteria.Rendered r = (criteria == null ? Criteria.of() : criteria).render(desc);
        String sql = "select 1 from " + desc.table() + r.where() + " limit 1";
        return executor.count(assembled.name("existsBy"), assembled.dialect().rewritePaging(sql),
                r.args()) > 0;
    }

    /**
     * 按字段等值判断存在性。
     *
     * @param field 字段名或列名
     * @param value 比较值；null 表示 {@code is null}
     * @return true 表示至少有一行
     */
    public boolean existsBy(String field, Object value) {
        return existsBy(Criteria.of().eq(field, value));
    }

    /**
     * 按条件批量更新：把满足条件的行改成同一组值。
     *
     * <p>典型场景是「把 7 天前创建的未激活用户批量禁用」——条件在运行期才知道，
     * 所以走 Criteria 而不是 JSON 语句。SET 的列同样要过白名单
     * （主键与逻辑删除列不可改），审计列自动刷新。
     *
     * <p><b>必须带条件</b>：空条件会抛异常而不是更新全表。这条限制不是洁癖——
     * 一次误传 {@code Map.of()} 就足以把整张表改掉，而且事务提交后无法回滚。
     * 真要全表更新，写一条显式 JSON 语句，让「全表」这件事在代码里看得见。
     *
     * @param criteria 条件，不能为空
     * @param values   字段 → 新值，至少一个
     * @return 受影响行数
     * @throws SqlSpecException 条件为空、字段不存在或不可更新
     */
    public int updateBy(Criteria criteria, Map<String, Object> values) {
        if (criteria == null || criteria.isEmpty()) {
            throw new SqlSpecException("updateBy 必须指定条件；更新全表请写显式语句");
        }
        CrudSql.Assign assign = CrudSql.assignments(desc, values);
        Criteria.Rendered r = criteria.render(desc);
        String sql = "update " + desc.table() + " set " + assign.sql() + r.where();
        List<Object> args = new ArrayList<>(assign.args());
        args.addAll(r.args());
        return executor.update(assembled.name("updateBy"), sql, args);
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
     * 流式消费查询结果：逐行回调，不把结果集加载成 List。
     *
     * <p>用于「处理百万行」这类场景：全量 {@link #query(String, Map)} 会先撑爆内存，
     * 而这里内存占用只与驱动的 fetchSize 有关。
     *
     * <p>两条使用约束（都来自「回调在连接归还之前执行」）：
     * <ul>
     *   <li><b>不要在回调里做远程调用或长阻塞</b>，否则连接一直被占着，池会耗尽；</li>
     *   <li><b>不要在回调里用同一个仓库发起查询</b>——同一线程会复用事务连接，
     *       MySQL 的流式结果集上再开语句会报「streaming result set still active」。
     *       需要关联数据时，先收集 id、回调结束后再批量查。</li>
     * </ul>
     *
     * <p>MySQL 要做真正的服务端游标，还需连接串 {@code useCursorFetch=true}，
     * 或换用带 fetchSize 的重载传 {@link Integer#MIN_VALUE}。
     *
     * @param stmt     语句名，不含命名空间
     * @param args     参数
     * @param consumer 行消费者，不能为 null
     * @return 消费的行数
     */
    public int forEach(String stmt, Map<String, Object> args, java.util.function.Consumer<T> consumer) {
        SqlV2.Prepared p = assembled.prepare(stmt, args);
        return executor.queryEach(assembled.name(stmt), p.sql(), p.args(), type, consumer);
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
     * 执行一条 JSON 里声明的更新/删除语句。
     *
     * <p>与 {@link #query(String, Map)} 对称：那条用于 SELECT，这条用于 UPDATE / DELETE /
     * 其它不返回结果集的语句。<b>不能</b>用来跑 SELECT——驱动会把结果集丢掉，
     * 看起来「执行成功」但其实什么都没拿到。
     *
     * <p>语句走完整编译管线，所以参数校验、白名单、指标、日志与查询语句一致；
     * {@code DELETE} 不会被逻辑删除改写器处理（改写器只认 SELECT / UPDATE），
     * 需要软删的语义请用 {@link #deleteBatch(List)} 或自定义 UPDATE 语句。
     *
     * @param stmt 语句名，不含命名空间
     * @param args 参数
     * @return 受影响行数
     * @throws SqlSpecException 语句不存在或参数校验失败
     */
    public int update(String stmt, Map<String, Object> args) {
        SqlV2.Prepared p = assembled.prepare(stmt, args);
        return executor.update(assembled.name(stmt), p.sql(), p.args());
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

    /**
     * 批量删除：有逻辑删除列时改写为 UPDATE，否则物理 DELETE。
     *
     * <p>与 {@link #deleteById(Object)} 同名同义——业务代码不该因为「用了逻辑删除」
     * 而分两个方法调用。分块执行（默认 500 个 id 一块），块之间<b>不是原子的</b>：
     * 第 3 块失败时前两块已提交，需要原子请用 {@link #tx(Runnable)} 包起来。
     *
     * <p>三件事由框架兜住，调用方不用管：去重、按主键升序（降低并发批量更新的死锁概率）、
     * 分块（Oracle 的 IN 上限是 1000）。
     *
     * @param ids 主键集合；null 或空返回 0，不访问数据库
     * @return 受影响行数合计；重复删除不影响结果（软删带 {@code deleted = 0} 条件）
     */
    public int deleteBatch(List<?> ids) {
        IdSet set = IdSet.of(ids);
        if (set.isEmpty()) {
            return 0;
        }
        List<SqlStatement> statements = desc.softDelete()
                ? CrudSql.softDeleteBatch(desc, set)
                : CrudSql.physicalDelete(desc, set);
        int total = 0;
        for (SqlStatement s : statements) {
            total += executor.update(assembled.name("deleteBatch"), s);
        }
        return total;
    }

    /**
     * 批量恢复：把回收站里的多条记录一次性恢复。
     *
     * <p>对未删除的记录调用不报错，只是刷新审计时间戳——与 {@link #restoreById(Object)}
     * 的幂等语义一致，这样「回收站全选恢复」不必先过滤。
     *
     * @param ids 主键集合；null 或空返回 0
     * @return 受影响行数合计
     * @throws SqlSpecException 实体没有逻辑删除列
     */
    public int restoreBatch(List<?> ids) {
        IdSet set = IdSet.of(ids);
        if (set.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (SqlStatement s : CrudSql.restoreBatch(desc, set)) {
            total += executor.update(assembled.name("restoreBatch"), s);
        }
        return total;
    }

    /**
     * 按主键集合查询。
     *
     * <p><b>返回顺序等于传入顺序</b>（去重后）：SQL 里按主键排序只是为了结果稳定，
     * 「按前端给的顺序渲染」是调用方的普遍预期，所以在内存里重排一次，O(n)。
     * 不存在的 id（从未存在或已被逻辑删除）直接跳过，不占位、不报错。
     *
     * <p>超过 500 个 id 时自动分块查询后合并——单条 SQL 塞十万个 id 会让连接被解析卡住。
     *
     * @param ids 主键集合；null 或空返回空列表，不访问数据库
     * @return 实体列表，最多与去重后的 id 数量相同
     */
    public List<T> findByIds(List<?> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        IdSet set = IdSet.of(ids);
        Map<Object, T> byId = new LinkedHashMap<>();
        for (List<Object> chunk : set.chunks()) {
            for (T entity : query("findByIds", Map.of("ids", chunk))) {
                Object raw = idOfEntity(entity);
                if (raw != null) {
                    byId.putIfAbsent(idKey(raw), entity);
                }
            }
        }
        List<T> out = new ArrayList<>(byId.size());
        for (Object id : new java.util.LinkedHashSet<>(ids)) {
            T entity = byId.get(idKey(id));
            if (entity != null) {
                out.add(entity);
            }
        }
        return out;
    }

    /**
     * 批量同值更新：把一批行的同一个字段改成同一个值。
     *
     * <p>与 {@link #updateBatch(List)} 的分工要看清楚：
     * <ul>
     *   <li>{@code updateBatch} 每行值不同，SQL 是 {@code CASE WHEN}，长度随行数增长；</li>
     *   <li>本方法所有行同一个值，一条 {@code UPDATE ... WHERE id IN (...)} 搞定，
     *       与行数无关。批量封号、批量改状态一律用这个。</li>
     * </ul>
     *
     * <p>字段名必须可更新：主键与逻辑删除列会被拒绝（改 deleted 等于绕过删除语义）。
     * 声明了审计列时自动刷新 {@code updated_at / updated_by}。
     *
     * @param values 字段名（或列名）→ 新值，至少一个
     * @param ids    主键集合；null 或空返回 0
     * @return 受影响行数合计
     * @throws SqlSpecException 字段不存在、不可更新，或 values 为空
     */
    public int updateFields(Map<String, Object> values, List<?> ids) {
        IdSet set = IdSet.of(ids);
        if (set.isEmpty()) {
            return 0;
        }
        CrudSql.Assign assign = CrudSql.assignments(desc, values);
        int total = 0;
        for (SqlStatement s : CrudSql.updateFields(desc, assign, set)) {
            total += executor.update(assembled.name("updateFields"), s);
        }
        return total;
    }

    /**
     * 单字段批量更新：{@link #updateFields(Map, List)} 的单列糖。
     *
     * @param field 字段名或列名
     * @param value 新值，可为 null（表示置空）
     * @param ids   主键集合
     * @return 受影响行数合计
     */
    public int updateField(String field, Object value, List<?> ids) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(field, value);
        return updateFields(values, ids);
    }

    /**
     * 批量 upsert：「有则更新，无则插入」。
     *
     * <p>两条路径，由 {@link Dialect#supportsUpsert()} 决定：
     * <ul>
     *   <li><b>原生</b>：MySQL {@code on duplicate key update}、PostgreSQL {@code on conflict}、
     *       H2 {@code merge into}——一条语句一批，一次往返；</li>
     *   <li><b>降级</b>：方言不支持时，在一个事务里先查哪些 id 已存在，再分流
     *       {@link #updateBatch(List)} 与 {@link #insertBatch(List)}。多一次查询，
     *       但语义一致，而且不会生成对方库不认的语法。</li>
     * </ul>
     *
     * <p><b>冲突判定列是主键</b>，因此要求主键已经有值（非自增主键，或导入数据自带 id）。
     * 自增主键 + 不带 id 的数据不适合 upsert——那种场景应该先用唯一业务键查重。
     *
     * <p><b>已知边界</b>：已逻辑删除的行不会被「复活」。原生路径会撞主键约束，
     * 降级路径会因为 {@code findByIds} 查不到它而尝试插入、同样撞主键。
     * 需要复活请显式走 {@link #restoreBatch(List)}。
     *
     * <p>按 500 行分块，块之间不原子；需要原子请包 {@link #tx(Runnable)}。
     *
     * @param list 待合并实体
     * @return 影响行数合计（更新 + 插入）
     * @throws SqlSpecException 没有任何可写入的列
     */
    public int upsertBatch(List<T> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        if (desc.idGenerated()) {
            throw new SqlSpecException(desc.type().getSimpleName()
                    + " 的主键由数据库生成，无法 upsert：冲突判定列必须是调用方给得出的键。"
                    + "请改用「按唯一业务键查重 + insert/update」，或给实体配一个业务主键");
        }
        for (T e : list) {
            desc.fillAudit(e, true);
        }
        if (!assembled.dialect().supportsUpsert()) {
            return upsertFallback(list);
        }
        int total = 0;
        for (CrudSql.InsertChunk chunk : CrudSql.insertChunks(desc, list, 500)) {
            List<String> updatable = new ArrayList<>();
            for (String c : chunk.columns()) {
                if (!c.equals(desc.idColumn())) {
                    updatable.add(c);
                }
            }
            if (updatable.isEmpty()) {
                // 只有主键一列，没有可覆盖的列，等价于普通插入
                total += executor.update(assembled.name("upsertBatch"), chunk.sql(), chunk.args());
                continue;
            }
            int rows = chunk.args().size() / chunk.columns().size();
            String sql = assembled.dialect().upsertStatement(chunk.sql(), desc.table(), chunk.columns(),
                    List.of(desc.idColumn()), updatable, rows);
            total += executor.update(assembled.name("upsertBatch"), sql, chunk.args());
        }
        return total;
    }

    /**
     * 没有原生 upsert 时的降级实现：同一事务内「查存在 → 分流 → 更新/插入」。
     *
     * <p>为什么要放在一个事务里：查询与写入之间如果有并发插入，分流结果会过期。
     * 事务不能消除这个窗口，但能把「更新到一半失败」的中间态收掉；
     * 真正的并发冲突交给主键约束兜底（抛异常而不是悄悄写脏数据）。
     */
    private int upsertFallback(List<T> list) {
        return txGet(() -> {
            List<Object> ids = new ArrayList<>(list.size());
            for (T e : list) {
                long id = idOf(e);
                if (id != 0L) {
                    ids.add(id);
                }
            }
            Set<Object> existing = new java.util.HashSet<>();
            for (T e : findByIds(ids)) {
                existing.add(idKey(idOfEntity(e)));
            }
            List<T> updates = new ArrayList<>();
            List<T> inserts = new ArrayList<>();
            for (T e : list) {
                long id = idOf(e);
                if (id != 0L && existing.contains(idKey(id))) {
                    updates.add(e);
                } else {
                    inserts.add(e);
                }
            }
            return updateBatch(updates) + insertBatch(inserts);
        });
    }

    /**
     * 游标分页：从 {@code lastId} 之后继续取一页，用于大导出。
     *
     * <p><b>为什么不用 {@code findPage}</b>：{@code LIMIT/OFFSET} 的成本是「翻过的行数」，
     * 第 5000 页要白扫十万行；游标靠主键索引定位，每页固定只读一页。
     * 代价是不支持跳页——只能一页一页往后，导出场景正好够用。
     *
     * <p><b>终止条件是「空页」而不是「不足一页」</b>：有逻辑删除列时，某一页可能因为过滤
     * 而少于 {@code limit}，但后面还有数据。用 {@code size < limit} 判断会把导出截断，
     * 而且不报错。
     *
     * <pre>{@code
     * long cursor = 0;
     * while (true) {
     *     List<User> batch = repo.findAfter(cursor, 1000);
     *     if (batch.isEmpty()) break;
     *     write(batch);
     *     cursor = repo.idOf(batch.get(batch.size() - 1));  // 或直接取实体 id 字段
     * }
     * }</pre>
     *
     * @param lastId 上一页最后一个主键；首页传 0（或比所有 id 小的值）
     * @param limit  每页条数，小于 1 按 1
     * @return 实体列表，按主键升序
     */
    public List<T> findAfter(Object lastId, int limit) {
        SqlStatement s = CrudSql.findAfter(desc, lastId == null ? 0L : lastId, limit);
        // LIMIT 是 MySQL 写法，Oracle / 达梦要靠方言翻译成 fetch first
        String sql = assembled.dialect().rewritePaging(s.sql());
        return executor.query(assembled.name("findAfter"), sql, s.args(), type);
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

    /** 读实体主键，读不到返回 null；{@link #findByIds} 建索引时用，不抛异常 */
    private Object idOfEntity(T entity) {
        try {
            return desc.idField().get(entity);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * 主键归一化，用于 Map 查找。
     *
     * <p>调用方传 {@code List.of(1L)} 还是 {@code List.of(1)} 都该命中同一条记录，
     * 而 {@code Integer(1).equals(Long(1))} 是 false。数字统一降成 {@code long}，
     * 非数字主键（字符串主键）原样保留。
     */
    private static Object idKey(Object id) {
        return id instanceof Number n ? (Object) n.longValue() : id;
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
