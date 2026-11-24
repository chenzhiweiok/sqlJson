# sqlJson

用 JSON 声明 SQL 与映射的轻量持久层。SQL 是唯一主体，参数描述贴在占位符旁边，编译成参数化 SQL 后执行。

三条贯穿全项目的取舍：

- **SQL 要能被看见**，而不是被 API 藏起来——所以复杂查询直接写 JSON，不发明一套 DSL；
- **值一律参数化**，标识符只能来自白名单，拼接出口只有 `params` 里的 `ident` / `raw` 两处；
- **每层能单独测**：`CrudSql` 只需元数据、`SqlV2` 只需一段 JSON、`RepositoryAssembler` 只需 classpath，
  凡是"必须起数据库才能测"的逻辑，就说明它被放错了层。

当前版本 `1.0.0`，Java 17。已发布到 GitHub Packages（私有包，拉取需要令牌）。

## 安装

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/chenzhiweiok/sqlJson</url>
  </repository>
</repositories>

<dependency>
  <groupId>io.github.chenzhiweiok</groupId>
  <artifactId>sqljson-core</artifactId>
  <version>1.0.0</version>
</dependency>
<!-- Spring 集成：把 artifactId 换成 sqljson-spring -->
```

包是私有的，`~/.m2/settings.xml` 里需要一个有 `read:packages` 权限的 GitHub 令牌：

```xml
<server>
  <id>github</id>
  <username>你的 GitHub 用户名</username>
  <password>ghp_xxx</password>
</server>
```

## 模块

```
sqlJson (parent)
├── sqljson-core       内核：JSON → SQL、注解仓库、事务、批量、条件、指标、join。零 Spring 依赖
├── sqljson-spring     @EnableSqlJson：扫描实体、把仓库注册成 Bean
└── sqljson-demo       业务示例：用户关系系统（Spring Boot 3 + 登录 + 回收站）
```

## 快速开始

```bash
mvn -q clean package
./sqljson-demo/run.sh          # 启动后访问 http://localhost:8077
```

演示账号：`邱老师 / 123456`（管理员），其余用户密码同为 `123456`，角色是成员。

数据库连接在 `sqljson-demo/src/main/resources/application.yml`，可用 `DEMO_DB_USER` / `DEMO_DB_PASSWORD` 覆盖。
**把密码写进配置文件只适合演示，生产请走密钥管理。**

## 一、常见用法（按场景）

每节都是一个能直接抄的最小片段；边界只写一句，细节在后面各章。

### 1. 定义实体、装配仓库

```java
@SqlJson(value = "user", table = "sys_user", resource = "sqls/sys_user.json")
public class SysUser {
    @SqlColumn(id = true, generated = true) Long id;
    @SqlColumn("user_name") String userName;
    @SqlColumn(deleted = true) Integer deleted;
    @SqlColumn(createdAt = true) LocalDateTime createdAt;
}

// 启动时装配一次并缓存（Spring 下就是单例 Bean），不要放在请求路径里
SqlJsonRepository<SysUser> users = SqlJsonRepository.of(SysUser.class, dataSource);
```

装配是重量级操作：读注解、解析资源 JSON、编译全部语句。装完的实例只读，可多线程共享。

### 2. 新增 / 按主键改 / 查一条

```java
long id = users.insert(user);       // 自增主键回填到实体，同时作为返回值
users.updateById(patch);            // 只更新非空字段；实体必须带主键
SysUser u = users.findById(id);     // 找不到返回 null，不抛异常
List<SysUser> all = users.findAll();
```

`updateById` 是部分更新，**无法用 null 清空列**——要清空就写一条显式语句。

### 3. 分页

```java
PageResult<SysUser> page = users.findPage(1, 20);   // 默认语句：全表按主键排序

Map<String, Object> args = new LinkedHashMap<>();
args.put("keyword", Like.prefix("张"));             // 前缀匹配，可走索引
args.put("dept", "技术部");
PageResult<SysUser> filtered = users.page("findPage", 1, 20, args);
```

`pageNo / pageSize` 不进 SQL 占位符，由引擎换算成 limit/offset；总数从主 SQL 推导，已删数据不计入。

### 4. 按 ID 集合查 / 批量插入

```java
List<SysUser> some = users.findByIds(List.of(3L, 1L));  // 返回顺序 = 传入顺序
users.insertBatch(list);                                 // 多行 VALUES，500 行一批
```

`findByIds` 对空集合直接返回空列表，不访问数据库；超过 500 个 id 自动分块合并。

### 5. 批量改同一个值

```java
users.updateField("status", -1, List.of(1L, 2L, 3L));
users.updateFields(Map.of("status", -1, "dept", "已归档"), ids);
```

每行值不同用 `updateBatch(list)`（CASE WHEN，SQL 长度随行数增长）；所有行同值用这个，一条语句解决。

### 6. 删除与回收站

```java
users.deleteById(id);        // 有软删列 → update deleted = 1；否则物理 DELETE
users.deleteBatch(ids);      // 勾选多条：幂等、去重、500 分块
users.findDeleted(50);       // 回收站列表
users.restoreById(id);
users.restoreBatch(ids);     // 回收站批量恢复
```

批量删除**块之间不原子**（第 3 块失败时前两块已提交），要原子请包 `tx`。

### 7. 计数与存在性

```java
long active = users.countBy("status", "ACTIVE");
long adults = users.countBy(Criteria.of().ge("age", 18));
long total  = users.countAll();
boolean dup = users.existsBy("phone", phone);    // select 1 ... limit 1
```

只关心"有没有"时不要用 count：大表 `count(*)` 要扫完整个匹配集，`existsBy` 读到第一行就停。

### 8. 条件更新

```java
int n = users.updateBy(
        Criteria.of().eq("status", "inactive").lt("createdAt", sevenDaysAgo),
        Map.of("status", "disabled"));
```

**空条件会抛异常而不是改全表**——这是刻意的；要全表更新就写一条显式语句。

### 9. 导入：有则更新、无则插入

```java
users.upsertBatch(rows);
// MySQL: ON DUPLICATE KEY UPDATE / PostgreSQL: ON CONFLICT / H2: MERGE INTO
// Oracle、达梦没有原生语法时自动降级为「查存在 → 分流 update/insert」
```

要求**非自增主键**：自增实体的"冲突键"调用方给不出来，会直接报错而不是生成语义不对的 SQL。

### 10. 导出：游标 + 流式

```java
long cursor = 0;
while (true) {
    List<SysUser> batch = users.findAfter(cursor, 1000);
    if (batch.isEmpty()) break;                  // 终止条件是空页，不是 size < limit
    batch.forEach(writer::write);
    cursor = batch.get(batch.size() - 1).getId();
}

// 或者不落 List，逐行消费
users.forEach("findAll", Map.of(), writer::write);
```

游标只能向后翻，不能跳页；`forEach` 的回调在连接归还前执行，别在里面做远程调用或再查同一个仓库。

### 11. 事务与级联

```java
users.tx(() -> {
    relations.deleteByUserId(userId);    // 先删子表
    users.deleteById(userId);            // 再删主表
});

int rows = users.txGet(() -> users.updateField("status", 0, ids));
```

事务块内所有仓库共用同一连接，所以跨仓库天然在一个事务里（前提是同一个库）。

### 12. Spring 里注入

```java
@EnableSqlJson(basePackages = "com.demo.entity")
public class App { }

public UserRepository(@Qualifier("userSqlJsonRepository")
                      SqlJsonRepository<SysUser> repo) { ... }
```

必须写 `@Qualifier`：BeanDefinition 不携带泛型，容器分不清两个 `SqlJsonRepository`。

### 13. 排障：看编译出来的 SQL、找慢语句

```java
users.explain("findPage", args);    // sql -- args=[...]，只渲染不执行
users.statements();                 // 这个实体到底装了哪些语句
users.metrics().topByP99(10);       // 按 SQL 指纹归并的慢查询
```

"语句没生效"先看 `statements()`；"为什么慢"看 `metrics()`——它按指纹把不同参数的同一语句分开统计。

## 二、JSON 怎么写

```json
{
  "user": {
    "findPage": {
      "sql": [
        "SELECT id, user_name, age, balance",
        "  FROM t_user",
        " WHERE 1=1",
        "   AND (#1 IS NULL OR user_name LIKE #1)   -- [1] string keyword 姓名模糊",
        "   AND (#2 IS NULL OR age >= #2)           -- [2] int    minAge  最小年龄",
        " ORDER BY id ASC"
      ],
      "pagination": { "style": "page", "pageParam": "pageNo", "sizeParam": "pageSize" }
    }
  }
}
```

| 写法 | 含义 | 渲染 |
|---|---|---|
| `#1` `#2` | 位置参数，对应描述第 n 项 | `?` |
| `#{item}` | 整个实体，按所在区展开 | 列名 / `?,?` / `a = ?, b = ?` |
| `#{item.name}` | 实体属性 | `?` |
| `#{ids}` | 集合（`findByIds` 用） | `?, ?, ?`，逐个绑定 |
| `{{var}}` | 编译期模板变量 | 原样替换 |

**只有命名占位符会展开集合**：`#{ids}` 里的 `List` 会渲染成多个 `?`；`#1` 是"单个值"通道，
塞一个 List 进去会把整个列表当成一个参数交给驱动，得到的是类型错误。这条差异是硬约束。

行尾注释 `-- [1] string keyword 说明` 就是参数文档；`params` 数组可覆盖类型与白名单（`enum`、`type: ident|raw`）。

安全策略：表名列名只能来自白名单正则，所有值走 `?`；`ident` / `raw` 是唯一允许拼接的类型，
且在描述里显式声明——审 `params` 就能审完所有拼接点。

### 多条件 + 模糊查询

写 `LIKE #{keyword}` 时，有三件事必须有人负责：**加 `%`、转义通配符、决定匹配模式**。
放在业务代码里，三件都会漏——而且都不报错，只是结果悄悄不对。所以框架把它们收到参数类型上：

| 声明 | 生成的模式串 | 能否用 B+Tree 索引 |
|---|---|---|
| `-- [1] like keyword` | `%值%`（包含） | ✘ |
| `-- [1] likeprefix keyword` | `值%`（前缀） | ✔ |
| `-- [1] likesuffix keyword` | `%值`（后缀） | ✘ |

```java
args.put("keyword", Like.prefix("张"));    // 张%   前缀，可走索引
args.put("keyword", Like.contains("张"));  // %张%  包含
args.put("keyword", Like.suffix(".pdf"));  // %.pdf 后缀
args.put("keyword", Like.raw("张%"));      // 原样，不转义（模式串已拼好时用）
```

三个行为是自动的：**转义**（输入 `50%` → `%50\%%`，只匹配字面 `50%`）、**空值短路**
（不传或空串绑 `null`，`IS NULL` 成立，条件整体不生效）、**同一参数出现两次不冲突**。

#### 为什么前缀能走索引，包含不行

B+Tree 按字典序存储。`张%` 能定位到"以张开头的区间"的起点，然后顺序读若干行；
`%张%` 起点不确定，优化器只能全表扫。所以工程上有两条护栏：搜索框少于 2 个字符不查；
`%x%` + 深分页基本无解，数据量到这个级别该交给全文索引或 ES。

#### 跨库提醒

转义符是 `\`，与 MySQL / PostgreSQL / H2 的 `LIKE` 默认值一致。
**Oracle 与达梦默认没有转义符**，需要显式写 `LIKE ? ESCAPE '\'`；引擎只替换占位符，不改写 SQL 文本，
所以这两个库要自己在语句里补 `ESCAPE`。

## 三、注解式仓库

```java
@SqlJson(value = "user", table = "sys_user", resource = "sqls/sys_user.json")
public class SysUser {
    @SqlColumn(id = true, generated = true) Long id;
    @SqlColumn("user_name") String userName;
    @SqlColumn(deleted = true) Integer deleted;
    @SqlColumn(createdAt = true) LocalDateTime createdAt;
    @SqlColumn(updatedAt = true) LocalDateTime updatedAt;
    @SqlColumn(createdBy = true) String createdBy;
    @SqlColumn(updatedBy = true) String updatedBy;
}
```

注解在运行时生成一份 v2 格式 JSON，再交给同一个编译引擎——注解版与手写 JSON 走完全相同的路径，行为不会分叉。

`resource` 里的同名语句优先于默认 CRUD，所以想给 `findPage` 加条件，不需要改注解。

默认生成 **7 条**语句：`insert`、`updateById`、`deleteById`、`findById`、`findByIds`、`findAll`、`findPage`。

## 四、能力清单

### 1. 逻辑删除 / 审计

| 声明 | 行为 |
|---|---|
| `@SqlColumn(deleted = true)` | 查询自动追加 `deleted = 0`；`deleteById` 改写为 `update ... set deleted = 1` |
| `createdAt / updatedAt / createdBy / updatedBy` | insert 填创建+更新，update 只刷新更新字段；显式传值不覆盖 |

改写挂在编译后钩子（`SqlV2.rewrite`）上，所以分页的 count 语句也继承同一份过滤，不会把已删数据算进总数。

**注意**：改写器只作用于经 `SqlV2` 编译的语句。`CrudSql`、`Criteria` 直接生成的 SQL 会自己补
`deleted = 0`——加新方法时必须记得这一点，漏了不会报错，只会把已删数据算进去。

### 2. 批量：按 ID 集合

```java
repo.deleteBatch(List.of(1L, 2L, 3L));            // 有软删列走 UPDATE，否则物理 DELETE
repo.restoreBatch(List.of(1L, 2L));               // 回收站批量恢复
repo.findByIds(List.of(3L, 1L));                  // 返回顺序 = 传入顺序
repo.updateField("status", -1, List.of(1L, 2L));  // 所有行改成同一个值
repo.updateFields(Map.of("status", -1, "dept", "已归档"), ids);
repo.findAfter(0L, 1000);                         // 游标分页，见下文「4. 游标分页」
```

| 方法 | 说明 |
|---|---|
| `int deleteBatch(List<?>)` | 幂等（软删带 `deleted = 0`）；空集合返回 0，不访问数据库 |
| `int restoreBatch(List<?>)` | 未删除的记录调用只刷新审计字段，接口保持幂等 |
| `List<T> findByIds(List<?>)` | 去重后按传入顺序返回；不存在的 id 跳过；>500 自动分块合并 |
| `int updateFields(Map, List<?>)` | 主键与 `deleted` 列禁止修改；自动刷新审计列 |
| `int updateField(String, Object, List<?>)` | 单列糖，允许传 null 表示置空 |

三件事由框架兜住，调用方不用管：

1. **去重**：`IN (1,1,1)` 不报错但会让影响行数看着奇怪，也让参数白绑；
2. **按主键升序**：并发批量更新同一批 id 时，如果两个事务加锁顺序相反，即使操作互不冲突也可能死锁；
   统一升序会把死锁降级成等待；
3. **500 一条分块**：Oracle 的 `IN` 上限是 1000，其它库受 SQL 文本长度与解析开销约束。

**块之间不是原子的**：第 3 块失败时前两块已提交。需要原子请包事务：

```java
repo.tx(() -> repo.deleteBatch(ids));
```

`updateBatch`（每行值不同，CASE WHEN）和 `updateFields`（同值）的分工要看清：
一千行批量封号用后者，一条语句搞定，长度与行数无关。

### 3. 条件：Criteria

运行期才知道条件的场景（"7 天前创建的未激活用户批量禁用"），用 `Criteria`：

```java
repo.countBy(Criteria.of().eq("status", "ACTIVE").gt("age", 30));
repo.existsBy("phone", "13800000000");
repo.updateBy(Criteria.of().eq("status", "inactive").lt("createdAt", sevenDaysAgo),
              Map.of("status", "disabled"));
```

| 操作符 | 说明 |
|---|---|
| `eq` / `ne` | 值为 null 时自动变成 `is null` / `is not null` |
| `gt` / `ge` / `lt` / `le` | 值为 null 直接抛异常（`> null` 永远不成立，静默变成查不到更难查） |
| `in` | 空集合渲染成 `1 = 0`——匹配不到任何行，绝不退化成"不加条件" |
| `isNull` / `isNotNull` | 显式表达 |
| `like` | 复用 `Like`，转义规则不重复实现 |

三条规则：

- **字段名走白名单**：`eq("age", 18)` 里的 `age` 经实体元数据解析成列名，也接受 `user_name` 这种列名写法；
  写错立刻抛异常并列出可用字段。拼进 SQL 的列名永远来自元数据，不来自调用方字符串；
- **软删过滤自动追加**：`render(desc)` 统一补 `deleted = 0`，三个消费方都不用管；
- **条件之间只有 AND**：OR 会让"自动补软删"的优先级变得危险（需要括号与嵌套），
  真实业务里批量统计/更新几乎都是 AND；需要 OR 写一条 JSON 语句，那里能精确表达括号。

`countBy` 与 `existsBy` 的成本不同，别混用：

| 方法 | SQL | 成本 |
|---|---|---|
| `long countBy(...)` | `select count(*) ...` | 扫完整个匹配集，O(n) |
| `boolean existsBy(...)` | `select 1 ... limit 1` | 读到第一行就停 |

`existsBy` 的 `limit 1` 会经方言翻译（Oracle / 达梦走 `fetch first 1 rows only`），不是硬拼的。

`updateBy` **拒绝空条件**：一次误传 `Map.of()` 就足以改掉整张表，而且提交后无法回滚。
真要全表更新，写一条显式 JSON 语句，让"全表"这件事在代码里看得见。

### 4. 游标分页：`findAfter`

`LIMIT/OFFSET` 的成本是"翻过的行数"：第 5000 页（每页 20 条）要白扫十万行。
游标靠主键索引定位，每页固定只读一页，代价是不支持跳页——导出场景正好够用。

```java
long cursor = 0;
while (true) {
    List<User> batch = repo.findAfter(cursor, 1000);
    if (batch.isEmpty()) break;            // 终止条件是"空页"，不是 size < limit
    write(batch);
    cursor = batch.get(batch.size() - 1).getId();
}
```

**为什么不能用 `size < limit` 判断结束**：有逻辑删除列时，某一页可能因为过滤而少于 `limit`，
但后面还有数据。用 `size < limit` 会把导出截断，而且不报错——这类 bug 只有对账时才会发现。

另外两点：`ORDER BY` 的列要是索引的最后一列（主键天然满足）；千万行导出不要放在同一个长事务里，
undo / MVCC 会膨胀，每批独立读、允许轻微快照不一致更实际。

### 5. upsert

```java
repo.upsertBatch(List.of(new DictItem(1, "a", "新名"), new DictItem(3, "c", "丙")));
```

| 方言 | 生成 |
|---|---|
| MySQL | `INSERT ... ON DUPLICATE KEY UPDATE col = VALUES(col)` |
| PostgreSQL | `INSERT ... ON CONFLICT (id) DO UPDATE SET col = EXCLUDED.col` |
| H2 | `MERGE INTO t (cols) KEY (id) VALUES (...), (...)` |
| 其它（Oracle / 达梦） | 降级为事务内「查存在 → 分流 update / insert」 |

**冲突判定列是主键，所以自增主键的实体不能 upsert**——新行的键由数据库生成，
"冲突"没有意义。这种情况会直接抛异常并说明原因，而不是静默走一条语义不对的路径。
需要按唯一业务键合并时，先按业务键查重，再分别 insert / update。

MySQL 用 `VALUES(col)` 而不是 8.0.19+ 的行别名：前者在 5.7 与 8.x 上都成立，
后者虽然更新但会放弃 5.7。8.0.20 起 `VALUES()` 有 deprecation warning，功能正常。

### 6. 流式读取

```java
int rows = repo.forEach("findAll", Map.of(), user -> writer.write(user));
```

逐行映射、逐行消费，内存占用只与驱动的 fetchSize 有关，不随结果集增长。

三条约束都来自"回调在连接归还之前执行"：

1. **不要在回调里做远程调用或长阻塞**：连接一直被占着，池很快耗尽；
2. **不要在回调里用同一个仓库再查**：同一线程复用事务连接，MySQL 的流式结果集上再开语句会报
   `streaming result set still active`；需要关联数据就先收集 id，回调结束后批量查；
3. **MySQL 要真流式**，需要连接串 `useCursorFetch=true` 且 fetchSize > 0，或传 `Integer.MIN_VALUE`。
   默认驱动会把整个结果集先拉到客户端，"看起来在流式"其实不是。

### 7. 事务与级联

```java
repo.tx(() -> {
    relationRepo.deleteByUserId(userId);   // 先删子表
    userRepo.deleteById(userId);           // 再删主表
});
```

连接放 ThreadLocal，事务块内所有仓库共用同一个连接，所以跨仓库天然在一个事务里，
不需要传连接，也不需要分布式事务（前提是同一个库）。`txGet(Supplier)` 用于需要返回值的场景；
嵌套调用用 Savepoint 隔离，内层失败只回滚内层。

demo 里两种删除策略并存，作为对照：

- `DELETE /api/users/{id}`：有关联就拒绝（保守，日常操作）；
- `DELETE /api/users/{id}/cascade`：连关系一起删（显式，后台清理）。

顺序不能反：先主表后子表，一旦子表失败，回滚前会短暂出现"用户已删、关系还在"的悬挂数据。

### 8. 慢 SQL 治理

```java
SqlMetrics m = repo.metrics();
m.topByTotal(10);   // 总耗时最高
m.topByP99(10);     // P99 最差
m.summary();        // 调用数 / 错误数 / 慢查询数
```

按 **SQL 指纹**（去字面量、折叠空白）归并，而不是按语句 id：同一个 id 在不同参数下会渲染出不同 SQL
（分页 offset、ident 拼接），执行计划可能完全不同。每条语句保留最近 512 个耗时样本算 P99。
`-Dsqljson.metrics=false` 可关闭。

新增的批量与条件方法都以独立语句名记录（`countBy`、`updateFields`、`deleteBatch`…），
所以在指标里能和 `findPage.count` 分开看。

### 9. 扁平 join 组装

```java
List<Map<String, Object>> rows = repo.queryMaps("user.withRelations", args);
List<UserView> views = JoinAssembler.of(rows, "id", UserView.class)
        .many("relations", RelationView.class, "rel_id", "rel_")
        .assemble();
```

只做两层：主对象 + 子集合。不生成代理、不做懒加载、不做级联保存——层级再深就该拆成两条 SQL。
子表列需要起别名（`rel_id`），`prefix` 会被剥掉再映射。

### 10. Spring 注入

```java
@SpringBootApplication
@EnableSqlJson(basePackages = "com.demo.relation.entity")
public class DemoApplication { }
```

扫描 `@SqlJson` 实体，注册名为 `<命名空间>SqlJsonRepository` 的 Bean。

```java
public UserRepository(@Qualifier("userSqlJsonRepository") SqlJsonRepository<SysUser> repo) { ... }
```

**必须用 `@Qualifier`**：BeanDefinition 无法携带泛型信息，容器看到的只是两个 `SqlJsonRepository`，
按 `SqlJsonRepository<SysUser>` 注入时无法消歧。这是 Spring 对 instanceSupplier 型 Bean 的固有限制。

### 11. 方言

| 能力 | MySQL | PostgreSQL | H2 | Oracle / 达梦 |
|---|---|---|---|---|
| 分页 | `LIMIT n OFFSET m` | 同 MySQL | 同 MySQL | `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` |
| upsert | `ON DUPLICATE KEY UPDATE` | `ON CONFLICT ... EXCLUDED` | `MERGE INTO ... KEY` | 降级为查存在+分流 |

`Dialect.of("dm")` 之类按名字取，认不出的名字退回 MySQL 而不是抛异常——
启动期因为拼写错误起不来，比"分页可能不对"更难排查；要对上号看启动日志里的方言名。

### 12. 扩展存储注解：`@RedisJson` / `@MongoDbJson`

`EntityDescriptor` 是核心里唯一一份元数据，"元数据从哪个注解来"被抽成了 `EntityParser`。
加一种存储注解不用改 `EntityDescriptor`，也不会出现第二份字段反射逻辑。

```java
public final class RedisJsonParser implements EntityParser<RedisJson> {
    @Override public Class<RedisJson> annotationType() { return RedisJson.class; }

    @Override public EntityDescriptor parse(Class<?> type, RedisJson ann) {
        EntityDescriptor.Builder b = EntityDescriptor.builder(type)
                .namespace(ann.value())
                .container(ann.keyPrefix().isBlank() ? ann.value() : ann.keyPrefix());
        for (Field f : type.getDeclaredFields()) {
            if (f.getName().equals("id")) b.id(f, f.getName(), false);
            else b.column(f, f.getName());     // 保留 camelCase，不转下划线
        }
        return b.build();
    }
}
```

```java
EntityParsers.register(new RedisJsonParser());
```

三条规则：命中 0 个注解报错并列出已注册的；命中 2 个以上**报错**（同一份字段映射到两种存储本身就是设计问题）；
同一注解重复注册替换旧的。

> **边界**：解析器只解决"元数据从哪来"，不解决"怎么执行"。真正的 Redis 支持还需要仓库实现
> （键拼装、序列化、TTL）。那时更可能共用 `EntityDescriptor` 与 `StatementFactory`、各写各的执行层，
> 而不是让 `SqlJsonRepository` 长出分支。

## 五、代码组织

| 类 | 职责 |
|---|---|
| `SqlJson` / `SqlColumn` | 注解，使用者的入口 |
| `SqlJsonRepository` | 门面：编排四层，只做「方法名 → 语句 → 执行」。线程安全 |
| `RepositoryAssembler` / `Assembled` | 装配：注解 + 资源 + 编译 + 改写器；渲染入口 |
| `JdbcExecutor` | 连接、绑定、计时、日志、指标、结果映射、流式读取 |
| `CrudSql` | 逻辑删除 / 恢复 / 批量 / 同值更新 / 游标的 SQL 文本，不执行 |
| `Criteria` | 条件构建器：字段白名单解析 + 参数化 where 片段 |
| `IdSet` | 批量主键规整：去重、升序、分块 |
| `SqlV2` | 编译引擎：JSON → 参数化 SQL + args。实例装配后只读 |
| `EntityDescriptor` | 元数据容器 + 公开 Builder + 列视图缓存。**不认识注解** |
| `EntityParser` / `EntityParsers` | 注解 → 元数据的解析器接口与注册表 |
| `StatementFactory` + 七个实现 | 一条默认语句一个策略 |
| `DefaultStatementSet` | 按固定顺序装配七条策略，重名直接报错 |
| `Statements` | 语句生成的共用片段：列清单、主键条件、sql 数组 |
| `SoftDeleteRewriter` | 编译后改写：补 `deleted = 0` |
| `Dialect` | 分页与 upsert 的方言差异 |
| `BeanMapper` | 行 → 对象，按类缓存 Field |
| `JoinAssembler` | 扁平 join → 主对象 + 子集合，只做两层 |
| `SqlMetrics` | 语句级指标：指纹归并、P99、Top N |
| `SqlLog` | 日志出口，只依赖 SLF4J API |
| `TxContext` | 事务上下文 + 连接作用域 |
| `CurrentUser` | 审计字段的操作人，ThreadLocal |
| `PageResult` | 分页结果，`list` 永不为 null |
| `SqlSpecException` | 统一异常，规格错误与执行错误都走它 |
| `SqlJsonRegistrar` | 扫描 `@SqlJson`，把仓库注册成 Bean |

调用链只有一条：

```
注解/JSON → SqlV2 渲染 → 改写（逻辑删除/方言） → JDBC → BeanMapper 映射
批量/条件   → CrudSql / Criteria 生成 SQL → 同上（不经 SqlV2，需自己补软删条件）
```

### 包结构

```
io.github.jsonsql                  公开门面：注解、仓库、分页、异常、CurrentUser、Like、Dialect
io.github.jsonsql.assembly         装配：RepositoryAssembler、Assembled
io.github.jsonsql.criteria         条件构建：Criteria
io.github.jsonsql.jdbc             JDBC 执行与 CRUD SQL 文本：JdbcExecutor、CrudSql、SqlStatement
io.github.jsonsql.engine           SqlV2（编译引擎）、SoftDeleteRewriter
io.github.jsonsql.meta             元数据：EntityDescriptor、EntityParser(s)、SqlJsonParser
io.github.jsonsql.meta.statement   StatementFactory + 七个内置实现
io.github.jsonsql.mapping          BeanMapper、JoinAssembler
io.github.jsonsql.support          SqlLog、SqlMetrics、TxContext、IdSet
```

依赖方向单向：`assembly` → `engine / meta / support`；`jdbc` → `engine / meta / mapping / support`；
子包可以依赖根包的共享类型（`SqlSpecException`、`Like`、`PageResult`），反过来不行。

### 四层职责

| 层 | 类 | 知道什么 | 不知道什么 | 变化频率 |
|---|---|---|---|---|
| 装配 | `RepositoryAssembler` → `Assembled` | 注解、资源 JSON、方言 | 连接、事务 | 启动一次 |
| 渲染 | `SqlV2` | 占位符、参数类型、分页 | 谁在调、连哪个库 | 改 SQL 时 |
| 生成 | `CrudSql` / `Criteria` | 表名、列、主键、审计、条件 | 连接、结果映射 | 改语义时 |
| 执行 | `JdbcExecutor` | `DataSource`、`sql + args` | 实体、注解、SQL 从哪来 | 换连接池/加埋点时 |

判断"这层有没有多干一件事"的标准：**把它单独 new 出来需要哪些依赖？**
`CrudSql` 只要 `EntityDescriptor`，`JdbcExecutor` 只要 `DataSource`，`RepositoryAssembler` 只要 classpath。

### 扩展默认语句：实现接口就行

```java
public class CountAllStatement implements StatementFactory {
    @Override public String name() { return "countAll"; }

    @Override public ObjectNode build(EntityDescriptor entity) {
        ObjectNode node = Statements.node();
        node.set("sql", Statements.sql("SELECT COUNT(*) AS cnt FROM " + entity.table()));
        return node;
    }
}
```

```java
// 1) 注解：跟着实体走，适合固定扩展
@SqlJson(value = "user", table = "t_user", statements = { CountAllStatement.class })
public class SysUser { ... }

// 2) 编程：运行期决定，适合按配置或多租户差异化
SqlJsonRepository.of(SysUser.class, ds, Dialect.mysql(), List.of(new CountAllStatement()));
```

同名即覆盖，且**保留原位置**——顺序稳定，日志和 `statements()` 对得上。
优先级：**resource 里的 JSON > 注解/参数声明的工厂 > 内置默认**。

不做 ServiceLoader 自动发现：那样"我明明写了实现，为什么语句没生效"会变成排查噩梦，
类路径、`META-INF/services`、构造器可见性，任何一处错都静默失败。

### 注释约定

- **类级**：做什么、为什么这么设计、线程安全、一个能跑的例子、已知的坑
- **方法级**：`@param` / `@return` / `@throws`，只写契约（空值、副作用、幂等），不复述方法名
- **行内**：解释"为什么"，不解释"是什么"
- **getter/setter 不写注释**

`mvn -pl sqljson-core javadoc:javadoc` 可生成完整文档，当前 0 error。

## 六、Demo：用户关系系统

Spring Boot 3 + 会话登录 + 角色 + 回收站，四个 tab：

- **用户**：姓名模糊 + 部门/状态筛选 + 分页 + 新建 + 删除（管理员）+ 按状态计数
- **关系**：建立/解除关系，不能自连、不能重复、目标必须存在
- **图谱**：圆形布局，节点大小=关系度数，实线好友/细线关注/虚线拉黑
- **回收站**：管理员可见，查看已删数据并恢复

分层单向：`web（搬数据）→ service（业务规则）→ repository（参数整理）→ core（执行）`。
service 里没有 SQL 关键字，repository 里没有"能不能这么做"的判断。

新增能力的落点示例：`countBy` 在 `/api/users/count`，级联删除在
`/api/users/{id}/cascade`（事务内先断关系再删人）。

## 七、测试

| 模块 | 用例数 | 覆盖 |
|---|---|---|
| `sqljson-core` | 171 | 占位符/类型/区域/模板/分页/逻辑删除/审计/批量/条件/游标/upsert/流式/方言/指标/join/分层 |
| `sqljson-spring` | 2 | 扫描装配、CRUD 与分页 |
| `sqljson-demo` | 9 | 登录、401/403、逻辑删除、回收站、恢复、计数、级联、业务规则 |

```bash
mvn -q test        # 182 个用例，全部基于 H2 内存库，不依赖外部 MySQL
```

新增能力的测试集中在四个类：`BatchOpsTest`（21）、`CountByTest`（14）、
`CriteriaOpsTest`（7）、`UpsertTest`（5）。SQL 文本类断言不需要数据库，行为类断言每个用例一个独立内存库。

## 八、踩过的坑（都是真问题）

**1. `SERVER__PORT` 会覆盖 `server.port`。** Spring Boot 的宽松绑定把它当成 `server.port`，
优先级高于 `application.yml`，表现为"启动成功但配置的端口打不开"。`run.sh` 里用 `env -u SERVER__PORT` 清掉。

**2. MySQL 首次连接做反向 DNS。** 在 DNS 受限的机器上每个新连接阻塞约 30 秒，看起来像"启动卡住且没日志"。
缓解：`DbWarmer` 预热 + 放宽 `connection-timeout`；根治是配 hosts 或换网络正常的机器。

**3. 不加 `-parameters` 编译，`@RequestParam` 会报 "Name for argument not specified"。**
父 POM 已开启；同时所有注解都写了显式名字，双保险。

**4. 整实体注入按列名取属性，驼峰字段会静默丢值。** `user_name` 取不到 `userName`，插入成功但字段是 null。
修复是列名 → 驼峰兜底查找。这类问题只有跑真实数据才暴露。

**5. 分页的 count 必须继承逻辑删除过滤。** 否则总数包含已删数据，翻页越翻越少。
钩子挂在渲染阶段而不是分页封装阶段，就是为了这个。

**6. 位置参数不展开集合。** `#1` 塞 List 会整个当参数绑给驱动，报类型错；只有 `#{ids}` 会展开成多个 `?`。
`findByIds` 的语句策略因此必须用命名占位符。

**7. 空集合渲染成字面量 `null`。** `IN (null)` 不报错也查不到任何行，看起来像"数据没了"。
所以批量方法在仓库层先判空短路，空 `IN` 在 `Criteria` 里显式渲染成 `1 = 0`。

**8. 游标分页不能用 `size < limit` 判断结束。** 软删过滤会让某页不足一页但后面还有数据，
用这个条件会静默截断导出。终止条件必须是"空页"。

**9. `CrudSql` / `Criteria` 直出的 SQL 不走改写器。** 逻辑删除条件要自己补；
框架内生成 `LIMIT` 的地方（`findDeleted`、`findAfter`）都已过方言，
但**手写 JSON 里的 `LIMIT #1` 不会**——像 demo 的 `recycleList` 那样直接写死分页，
在 Oracle / 达梦上会语法错，跨库时改成 `pagination` 声明。

## 九、边界

- 不做对象图：无懒加载、无级联保存、无一级/二级缓存
- 不做全功能 QueryWrapper：`Criteria` 只服务「条件计数 / 存在性 / 条件更新」这一窄口子，
  且只支持 AND；复杂查询写 JSON
- `Criteria` 不支持 OR / 子查询 / JOIN——需要时写语句，不要在 API 里堆表达式树
- 批量方法按 500 分块，**块之间不原子**；`findByIds` 跨块结果在内存合并
- upsert 要求非自增主键；不复活已逻辑删除的行（需显式 `restoreBatch`）
- 游标分页只支持向后翻，不能跳页
- MySQL 真流式需要连接串配合（`useCursorFetch=true`）
- `limit` 在 H2 / MySQL / PG 可用，Oracle / 达梦要走 `Dialect`
- 跨文件复用模板不支持，模板只在同一 JSON 内生效
- 登录是演示实现（固定盐 SHA-256），生产要换 BCrypt/Argon2 + CSRF 防护

判断一条新能力该不该加，只有一个标准：**它让 SQL 更容易被看见，还是更容易被隐藏？**
