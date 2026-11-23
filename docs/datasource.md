# 连接池兼容性与 Spring 事务集成

## 一、兼容性：只依赖 `javax.sql.DataSource`

仓库层只用到一个方法：`dataSource.getConnection()`。所以**任何 DataSource 实现都能直接接**，不需要适配代码。

| 连接池 | 支持 | 备注 |
|---|---|---|
| HikariCP | ✅ | Spring Boot 2+ 默认，吞吐最好 |
| **Druid** | ✅ | 监控最强：StatView、WallFilter、慢 SQL |
| Tomcat JDBC Pool | ✅ | Spring Boot 可 `spring.datasource.type` 切换 |
| DBCP2 | ✅ | 老项目常见 |
| C3P0 | ✅ | 老项目 |
| Oracle UCP | ✅ | 用 Oracle 时 |
| H2 `JdbcDataSource` | ✅ | 测试用（本项目 Demo 用的就是它） |
| JNDI DataSource | ✅ | 容器托管，`InitialContext.lookup` 拿到即可 |

## 二、Druid 配置

### 裸用

```java
DruidDataSource ds = new DruidDataSource();
ds.setUrl("jdbc:mysql://127.0.0.1:3306/demo");
ds.setUsername("root");
ds.setPassword("***");
ds.setInitialSize(5);
ds.setMinIdle(5);
ds.setMaxActive(20);
ds.setMaxWait(3000);
ds.setValidationQuery("SELECT 1");
ds.setTestWhileIdle(true);
ds.setPoolPreparedStatements(true);
ds.setMaxPoolPreparedStatementPerConnectionSize(20);
ds.setFilters("stat,wall");

SqlJsonRepository<User> repo = SqlJsonRepository.of(User.class, ds);
```

### Spring Boot

```yaml
spring:
  datasource:
    type: com.alibaba.druid.pool.DruidDataSource
    druid:
      url: jdbc:mysql://127.0.0.1:3306/demo
      username: root
      password: "***"
      initial-size: 5
      max-active: 20
      max-wait: 3000
      validation-query: SELECT 1
      test-while-idle: true
      pool-prepared-statements: true
      filters: stat,wall
      stat-view-servlet:
        enabled: true
```

## 三、四个坑

### 1. `removeAbandoned` 会误杀长事务（最容易踩）

Druid 的 `removeAbandoned=true` + `removeAbandonedTimeout=300` 会把「超过 5 分钟未归还」的连接强制回收。

而**我们的事务连接在整个 `tx(...)` 块内是不归还的**——这是设计使然。结果：批量导入跑了 6 分钟，Druid 把连接收走，程序报 `connection closed` 或 `Connection is null`，堆栈看起来和业务毫无关系。

对策：

```java
ds.setRemoveAbandoned(false);   // 长事务场景直接关掉
// 或者把 timeout 调得比最长事务还大
```

### 2. PSCache 有效，但别期望太高

我们的 SQL 文本由模板渲染产生。同一条语句因为「只注入非空字段」，不同字段组合会渲染出**少量不同的 SQL 文本**（`SET a = ?, b = ?` vs `SET a = ?`）。

`poolPreparedStatements=true` 能缓存这些变体，命中率取决于字段组合的离散度。建议开，但 `maxPoolPreparedStatementPerConnectionSize` 给 20 就够，别设几百——那只是把堆内存拿去存用不上的游标。

### 3. WallFilter 不会误伤

Druid 的 SQL 防火墙默认 `multiStatementAllow=false`（禁止多语句）。我们的编译产物**永远是单条语句**，值全部走 `?`，不会被拦。

反倒是如果哪天有人往 `sql` 数组里塞了两条语句，WallFilter 会第一个报出来——等于免费多了一道防注入检查。

### 4. StatFilter 正好补上我们缺的监控

`stat` 过滤器 + StatView 页面能直接看：每条 SQL 的执行次数、总耗时、最慢一次、并发数、执行错误。

我们没做慢 SQL 统计，这些正好由 Druid 提供。配合 `repo.explain("findPage", args)`（看编译后的 SQL 长什么样），排查链路是完整的：

```
StatView 发现慢 → 看 SQL 文本 → repo.explain 还原带参 SQL → 看模板对不对
```

## 四、Spring `@Transactional` 集成（关键）

默认 `TxContext` 用自己的 ThreadLocal。如果项目里同时用 Spring 的 `@Transactional`，会出问题：

> Spring 事务里调用 repo → repo 看不到 Spring 的事务，另开一个连接 → **事务不原子**。

因为两个事务上下文互不认识。

解决：装一个 `ConnectionSource` 桥接（框架已留好扩展点）。

```java
@Configuration
public class SqlJsonConfig {

    @Bean
    public SqlJsonRepository<User> userRepo(DataSource ds) {
        TxContext.use(new TxContext.ConnectionSource() {
            @Override
            public Connection acquire(DataSource d) throws SQLException {
                return DataSourceUtils.getConnection(d);      // 有事务则返回绑定的连接
            }

            @Override
            public void release(Connection c, DataSource d) {
                DataSourceUtils.releaseConnection(c, d);      // 有事务则不真关
            }

            @Override
            public boolean inTransaction(DataSource d) {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }
        });
        return SqlJsonRepository.of(User.class, ds);
    }
}
```

装完之后三种情况都正确：

| 场景 | 行为 |
|---|---|
| Spring `@Transactional` 内调 repo | 用 Spring 绑定的连接，原子性成立 |
| 没有 Spring 事务，调 `repo.tx(...)` | 走我们自己的事务（TxContext 的 ThreadLocal） |
| 两者都有 | `inTransaction()` 返回 true，`repo.tx` 不再自己开事务，直接并入 Spring 的 |

`ConnectionSource` 只有三个方法，也可以接 MyBatis 的 `SpringManagedTransaction`、JTA、或者自研的多数据源路由。

## 五、职责划分

| 职责 | 归谁 |
|---|---|
| 连接获取 / 归还 / 保活 / 超时 | 连接池（Druid / Hikari） |
| 事务边界 begin / commit / rollback | TxContext（无 Spring）或 Spring |
| 嵌套隔离 | TxContext 的 Savepoint |
| SQL 生成与参数化 | SqlV2 |
| SQL 统计、防火墙 | Druid 的 stat / wall 过滤器 |

分层是干净的：**连接池不关心事务语义，事务层不关心连接从哪来。**

## 六、容量规划的一句话提醒

非事务状态下，我们每次操作都「借一次、还一次」连接。所以 `maxActive` 要按**并发请求数**配，不是按仓库数量配。

一个常见误判：「我只有 3 个实体，配 5 个连接够了吧」——错。20 个并发请求同时查，就要 20 个连接，否则线程在 `maxWait` 上排队，表现为「接口偶发变慢」，而不是报错。

按这个估：`maxActive ≈ 峰值 QPS × 单次查询耗时(秒)`，再留 30% 余量。
