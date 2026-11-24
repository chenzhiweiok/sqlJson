# 变更日志

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。1.x 阶段内部包的调整按 minor 处理，
但破坏性变更一定会在「升级提示」里逐条列出——不靠读者自己 diff。

## [1.1.0] - 2026-11-24

这一版把「业务里高频但框架没给」的五类能力补齐，并借机把包结构理顺。

### 新增

**批量按 ID 操作**

- `deleteBatch(ids)`：有逻辑删除列时改写为 UPDATE，否则物理 DELETE；幂等
- `restoreBatch(ids)`：回收站批量恢复，对未删记录只刷新审计字段
- `findByIds(ids)`：返回顺序等于传入顺序，缺失 id 跳过，超过 500 自动分块合并
- `updateFields(values, ids)` / `updateField(field, value, ids)`：一批行改成同一个值
- 三者共用 `IdSet` 做去重、按主键升序（降低并发死锁概率）、500 条分块（Oracle IN 上限 1000）
- 默认语句从 6 条变 7 条，新增 `findByIds` 策略

**条件 API**

- `Criteria`：链式条件构建器，支持 `eq/ne/gt/ge/lt/le/in/isNull/isNotNull/like`
- `countBy(criteria)` / `countBy(field, value)` / `countAll()`
- `existsBy(criteria)` / `existsBy(field, value)`：生成 `select 1 ... limit 1`，不扫全表
- `updateBy(criteria, values)`：空条件直接抛异常，避免误改全表
- 字段名统一经实体元数据解析成列名，写错立刻报错并列出可用字段

**游标分页**

- `findAfter(lastId, limit)`：`where id > ? order by id limit n`，深分页不再白扫前面的行
- 终止条件必须是空页——软删过滤会让某页不足一页但后面还有数据

**upsert**

- `upsertBatch(list)`：MySQL `ON DUPLICATE KEY UPDATE`、PostgreSQL `ON CONFLICT`、
  H2 `MERGE INTO ... KEY`；其它方言自动降级为事务内「查存在 → 分流 update/insert」
- 冲突判定列是主键，因此**自增主键的实体直接报错**，而不是生成语义不对的 SQL

**流式读取**

- `forEach(stmt, args, consumer)`：逐行映射、逐行消费，内存占用与结果集大小无关
- 底层 `JdbcExecutor.queryEach` 支持自定义 fetchSize；MySQL 真流式需 `useCursorFetch=true`

**其它**

- `update(stmt, args)`：执行 JSON 里声明的 UPDATE / DELETE 语句（原先只有 SELECT 与裸 SQL 两条路）
- demo 新增 `GET /api/users/count`（按状态计数）与 `DELETE /api/users/{id}/cascade`（事务级联删除）

### 变更

- `EntityDescriptor` 预计算列视图（`columnByField` / `updateColumnSet` / `allColumns`），
  条件与批量路径不再每次重建列表
- `Dialect` 增加 `supportsUpsert()` / `upsertStatement()`，并新增 `h2()` 方言
- `findDeleted` 生成的 `LIMIT` 改走方言翻译（Oracle / 达梦可执行）
- 包结构重整：
  - 新增 `io.github.jsonsql.sql`：`CrudSql`、`SqlStatement`、`IdSet`——生成 SQL 文本但不依赖 JDBC
  - `Statements`、`DefaultStatementSet` 收进 `meta.statement`，与七个语句策略同包
  - 10 个包各补 `package-info.java`，写明职责边界与依赖方向

### 移除

- `Dialect.Simple`（无实例化）与接口上的 `PAGING` 常量（收敛为 `OracleLike` 私有）
- `EntityDescriptor.instantiate()`（与 `SqlJsonParser` 重复且未调用）
- `SqlV2.explain()`、`JdbcExecutor.dataSource()`、`SqlJsonRepository.engine()`
  ——后两者暴露了不该外露的连接与引擎，`engine()` 的注释还指向已删除的 MyBatis 桥接

### 升级提示

1. **包路径变更**（仅影响直接 import 过内部类型的代码）：

   | 1.0.0 | 1.1.0 |
   |---|---|
   | `io.github.jsonsql.jdbc.CrudSql` | `io.github.jsonsql.sql.CrudSql` |
   | `io.github.jsonsql.jdbc.SqlStatement` | `io.github.jsonsql.sql.SqlStatement` |
   | `io.github.jsonsql.support.IdSet` | `io.github.jsonsql.sql.IdSet` |
   | `io.github.jsonsql.meta.Statements` | `io.github.jsonsql.meta.statement.Statements` |
   | `io.github.jsonsql.meta.DefaultStatementSet` | `io.github.jsonsql.meta.statement.DefaultStatementSet` |

   只用 `@SqlJson` / `SqlJsonRepository` / `Criteria` 的业务代码**不受影响**。

2. `upsertBatch` 要求非自增主键；自增实体请先按唯一业务键查重再 insert/update。
3. `updateBy` 不接受空条件；全表更新请写显式语句。
4. 1.0.0 已发布且 GitHub Packages 不允许覆盖同名版本，请升级坐标到 1.1.0。

### 验证

182 个用例通过（core 171 / spring 2 / demo 9），`javadoc:javadoc` 0 error。
新增测试类：`BatchOpsTest`、`CountByTest`、`CriteriaOpsTest`、`UpsertTest`。

## [1.0.0] - 2026-11-24

首个发布版本。JSON 声明 SQL 与映射、注解式仓库、逻辑删除与审计字段、批量插入/更新、
分页方言、语句级指标（指纹 / P99 / Top N）、扁平 join 组装、Spring 注入，以及用户关系系统 demo。
