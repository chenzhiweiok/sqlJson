# sqlJson

用 JSON 声明 SQL 与映射的轻量持久层。SQL 是唯一主体，参数描述贴在占位符旁边，编译成参数化 SQL 后执行。

发布坐标（准备发往 Maven Central）：

```xml
<dependency>
  <groupId>io.github.chenzhiweiok</groupId>
  <artifactId>sqljson-core</artifactId>
  <version>1.0.0</version>
</dependency>
<!-- Spring 集成：把上面的 artifactId 换成 sqljson-spring -->
```

```
sqlJson (parent)
├── sqljson-core       框架内核：JSON → SQL、注解仓库、事务、分页、指标、join 组装。零 Spring 依赖
├── sqljson-spring     @EnableSqlJson：扫描实体、把仓库注册成 Bean
└── sqljson-demo       业务示例：用户关系系统（Spring Boot 3 + 登录 + 回收站）
```

## 快速开始

```bash
mvn -q clean package
./sqljson-demo/run.sh          # 启动后访问 http://localhost:8077
```

演示账号：`邱老师 / 123456`（管理员，可删除与恢复），其余用户密码同为 `123456`，角色是成员。

数据库连接在 `sqljson-demo/src/main/resources/application.yml`，可用环境变量覆盖：
`DEMO_DB_USER` / `DEMO_DB_PASSWORD`。**把密码写进配置文件只适合演示，生产请走密钥管理。**

## 一、JSON 怎么写

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
| `{{var}}` | 编译期模板变量 | 原样替换 |

行尾注释 `-- [1] string keyword 说明` 就是参数文档；`params` 数组可覆盖类型与白名单（`enum`、`type: ident|raw`）。

安全策略：表名列名只能来自白名单正则，所有值走 `?`；`ident` / `raw` 是唯一允许拼接的类型，且在描述里显式声明——审 `params` 就能审完所有拼接点。

### 多条件 + 模糊查询

写 `LIKE #{keyword}` 时，有三件事必须有人负责：**加 `%`、转义通配符、决定匹配模式**。
放在业务代码里，三件都会漏——而且都不报错，只是结果悄悄不对。所以框架把它们收到参数类型上：

| 声明 | 生成的模式串 | 能否用 B+Tree 索引 |
|---|---|---|
| `-- [1] like keyword` | `%值%`（包含） | ✘ |
| `-- [1] likeprefix keyword` | `值%`（前缀） | ✔ |
| `-- [1] likesuffix keyword` | `%值`（后缀） | ✘ |

```json
"findPage": {
  "sql": [
    "SELECT id, user_name, dept FROM sys_user",
    " WHERE 1=1",
    "   AND (#1 IS NULL OR user_name LIKE #1)   -- [1] like keyword 姓名模糊",
    "   AND (#2 IS NULL OR dept = #2)           -- [2] string dept 部门",
    " ORDER BY id"
  ]
}
```

三个行为是自动的，不需要业务代码配合：

1. **转义**：用户输入 `50%`，参数变成 `%50\%%`，只匹配字面「50%」，而不是匹配所有；
2. **空值短路**：不传或传空串，绑 `null`，`IS NULL` 成立，这个条件整体不生效；
3. **同一参数出现两次不冲突**：`#1` 既是 `IS NULL` 的判断值、又是 `LIKE` 的模式串，各自绑定各自的值。

想在 Java 侧表达意图，JSON 里就不用写 `type`：

```java
args.put("keyword", Like.prefix("张"));    // 张%   前缀，可走索引
args.put("keyword", Like.contains("张"));  // %张%  包含
args.put("keyword", Like.suffix(".pdf"));  // %.pdf 后缀
args.put("keyword", Like.raw("张%"));      // 原样，不转义（模式串已拼好时用）
```

**兼容性**：不写 `type` 的字符串参数仍是旧行为（原样传给数据库），已有语句升级后行为不变。

#### 为什么前缀能走索引，包含不行

B+Tree 按字典序存储。`张%` 能定位到「以张开头的区间」的起点，然后顺序读若干行；
`%张%` 起点不确定，优化器只能全表扫描（或扫全部索引项）。

所以工程上有两条护栏，比换数据库更实际：

- **搜索框少于 2 个字符不查**：`%a%` 在百万行表上等于全表扫，用户也不会因为前 1 个字得到有用结果；
- **`%x%` + 深分页基本无解**：`LIMIT 10000, 20` 要先把符合条件的十万行扫出来，再丢掉前一万条。
  数据量到这个级别，模糊搜索该交给全文索引或 ES，而不是继续优化 SQL。

#### 跨库提醒

转义符用的是 `\`，与 MySQL / PostgreSQL / H2 的 `LIKE` 默认值一致。
**Oracle 与达梦默认没有转义符**，需要显式写 `LIKE ? ESCAPE '\'`，否则 `\%` 里的反斜杠会被当成普通字符。
这一处引擎不自动改写——因为要动 SQL 文本，而引擎只替换占位符。跨这两个库时，在语句里把 `ESCAPE` 补上。

## 二、注解式仓库

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

注解在运行时生成一份 v2 格式 JSON，再交给同一个编译引擎——所以注解版与手写 JSON 走完全相同的路径，行为不会分叉。

`resource` 里的同名语句优先于默认 CRUD，所以想给 `findPage` 加条件，不需要改注解。

## 三、能力清单

### 1. 逻辑删除 / 审计 / 批量 / 方言

| 声明 | 行为 |
|---|---|
| `@SqlColumn(deleted = true)` | 查询自动追加 `deleted = 0`；`deleteById` 改写为 `update ... set deleted = 1`；提供 `restoreById`、`findDeleted` |
| `createdAt / updatedAt / createdBy / updatedBy` | insert 填创建+更新，update 只刷新更新字段；显式传值不覆盖 |
| `insertBatch` / `updateBatch` | 多行 VALUES / 一条 UPDATE + CASE WHEN |
| `Dialect.oracleLike()` | `LIMIT n OFFSET m` → `OFFSET m ROWS FETCH NEXT n ROWS ONLY` |

逻辑删除的改写挂在编译后钩子上（`SqlV2.rewrite`），所以分页的 count 语句也继承同一份过滤，不会把已删数据算进总数。

### 2. 慢 SQL 治理

```java
SqlMetrics m = repo.metrics();
m.topByTotal(10);   // 总耗时最高
m.topByP99(10);     // P99 最差
m.summary();        // 调用数 / 错误数 / 慢查询数
```

按 **SQL 指纹**（去字面量、折叠空白）归并，而不是按语句 id：同一个 id 在参数不同的情况下会渲染出不同 SQL（分页 offset、ident 拼接），执行计划可能完全不同。每条语句保留最近 512 个耗时样本算 P99。`-Dsqljson.metrics=false` 可关闭。

### 3. 扁平 join 组装

```java
List<Map<String, Object>> rows = repo.queryMaps("user.withRelations", args);
List<UserView> views = JoinAssembler.of(rows, "id", UserView.class)
        .many("relations", RelationView.class, "rel_id", "rel_")
        .assemble();
```

只做两层：主对象 + 子集合。不生成代理、不做懒加载、不做级联保存——层级再深就该拆成两条 SQL，而不是继续堆 join。子表列需要起别名（`rel_id`），`prefix` 会被剥掉再映射。

### 4. Spring 注入

```java
@SpringBootApplication
@EnableSqlJson(basePackages = "com.demo.relation.entity")
public class DemoApplication { }
```

扫描 `@SqlJson` 实体，注册名为 `<命名空间>SqlJsonRepository` 的 Bean。

```java
public UserRepository(@Qualifier("userSqlJsonRepository") SqlJsonRepository<SysUser> repo) { ... }
```

**必须用 `@Qualifier`**：BeanDefinition 无法携带泛型信息，容器看到的只是两个 `SqlJsonRepository`，按 `SqlJsonRepository<SysUser>` 注入时无法消歧。这是 Spring 对 instanceSupplier 型 Bean 的固有限制，不是配置没写对。

### 5. 扩展存储注解（预留）

## 四、代码组织

每个类的职责，一句话能定位：

| 类 | 职责 |
|---|---|
| `SqlJson` / `SqlColumn` | 注解，使用者的入口 |
| `SqlJsonRepository` | 门面：编排四层，只做「方法名 → 语句 → 执行」。线程安全 |
| `RepositoryAssembler` / `Assembled` | 装配：注解 + 资源 + 编译 + 改写器；渲染入口 |
| `JdbcExecutor` | 连接、绑定、计时、日志、指标、结果映射 |
| `CrudSql` | 逻辑删除 / 恢复 / 批量的 SQL 文本，不执行 |
| `SqlV2` | 编译引擎：JSON → 参数化 SQL + args。实例装配后只读 |
| `EntityDescriptor` | 元数据容器 + 公开 Builder。**不认识注解**，只负责校验与装载 |
| `EntityParser` / `EntityParsers` | 注解 → 元数据的解析器接口与注册表；`@SqlJson` 是默认的一个实现 |
| `StatementFactory` + 六个实现 | 一条默认语句一个策略：insert / updateById / deleteById / findById / findAll / findPage |
| `DefaultStatementSet` | 按固定顺序装配六条策略，重名直接报错 |
| `Statements` | 语句生成的共用片段：列清单、主键条件、sql 数组 |
| `SoftDeleteRewriter` | 编译后改写：补 `deleted = 0`、delete 改 update |
| `Dialect` | 分页方言，只翻译末尾的 limit 子句 |
| `BeanMapper` | 行 → 对象，按类缓存 Field |
| `JoinAssembler` | 扁平 join → 主对象 + 子集合，只做两层 |
| `SqlMetrics` | 语句级指标：指纹归并、P99、Top N |
| `SqlLog` | 日志出口，只依赖 SLF4J API，换实现不用改代码 |
| `TxContext` | 事务上下文 + `ConnectionSource` 扩展点（Spring 桥接就在这） |
| `CurrentUser` | 审计字段的操作人，ThreadLocal |
| `PageResult` | 分页结果，`list` 永不为 null |
| `SqlSpecException` | 统一异常，规格错误与执行错误都走它 |
| `SqlJsonRegistrar` | 扫描 `@SqlJson`，把仓库注册成 Bean |

调用链只有一条，不绕：

```
注解/JSON → SqlV2 渲染 → 改写（逻辑删除/方言） → JDBC → BeanMapper 映射
```

### 扩展存储注解：`@RedisJson` / `@MongoDbJson`

`EntityDescriptor` 是核心里唯一一份元数据，「元数据从哪个注解来」被抽成了
`EntityParser`。所以加一种存储注解，不用改 `EntityDescriptor`，也不会出现第二份字段反射逻辑。

```java
@Retention(RUNTIME)
@Target(TYPE)
public @interface RedisJson {
    String value();                   // 命名空间
    String keyPrefix() default "";    // 键前缀，语义等同表名
}

public final class RedisJsonParser implements EntityParser<RedisJson> {

    @Override
    public Class<RedisJson> annotationType() {
        return RedisJson.class;
    }

    @Override
    public EntityDescriptor parse(Class<?> type, RedisJson ann) {
        String prefix = ann.keyPrefix().isBlank() ? ann.value() : ann.keyPrefix();
        EntityDescriptor.Builder b = EntityDescriptor.builder(type)
                .namespace(ann.value())
                .container(prefix);
        for (Field f : type.getDeclaredFields()) {
            if (f.getName().equals("id")) {
                b.id(f, f.getName(), false);      // Redis 的主键由业务给，不是自增
            } else {
                b.column(f, f.getName());         // 保留 camelCase，不转下划线
            }
        }
        return b.build();
    }
}
```

注册一次，之后 `EntityDescriptor.of(RedisUser.class)` 自动认出它：

```java
EntityParsers.register(new RedisJsonParser());
```

三条规则：

| 规则 | 行为 |
|---|---|
| 命中 0 个注解 | 报错，消息里列出已注册的注解 + 提示实现 `EntityParser` |
| 命中 2 个以上 | **报错**，不静默取第一个（同一份字段映射到两种存储，本身就是设计问题） |
| 同一注解重复注册 | 替换旧的，用于测试或多环境覆盖 |

字段语义（主键 / 逻辑删除 / 审计）由 `Builder` 统一提供，各解析器共享；
**容器名与列名的命名策略由解析器自己决定**——SQL 转 snake_case，Redis / Mongo 通常保留原样，
所以这个默认值不能放在 `EntityDescriptor` 里。

> **边界要说清**：解析器只解决「元数据从哪来」，不解决「怎么执行」。
> 真正的 Redis 支持还需要一个仓库实现（键拼装、序列化、TTL），Mongo 需要一个
> 基于 `Document` 的仓库。那时更可能的做法是：共用 `EntityDescriptor` 与
> `StatementFactory`，各写各的执行层，而不是让 `SqlJsonRepository` 长出分支。

### 包结构

```
io.github.jsonsql                  公开门面：注解、仓库、分页、异常、CurrentUser、Like、Dialect
io.github.jsonsql.assembly         装配：RepositoryAssembler、Assembled
io.github.jsonsql.jdbc             JDBC 执行与 CRUD SQL 文本：JdbcExecutor、CrudSql、SqlStatement
io.github.jsonsql.engine           SqlV2（编译引擎）、SoftDeleteRewriter
io.github.jsonsql.meta             元数据：EntityDescriptor、EntityParser(s)、SqlJsonParser
io.github.jsonsql.meta.statement    StatementFactory + 六个内置实现
io.github.jsonsql.mapping           BeanMapper、JoinAssembler
io.github.jsonsql.support           SqlLog、SqlMetrics、TxContext
```

**根包只放「使用方要 import 的东西」**，其余按职责下沉。公开 API 的路径没有变，
所以这次拆包没有让 demo 改一行代码——拆的是实现，不是接口。

依赖方向单向：

```
assembly  →  engine / meta / support
jdbc      →  engine / meta / mapping / support
engine / meta / mapping / support  →  根包（共享类型：SqlSpecException、Like、PageResult）
SqlJsonRepository（根）  →  assembly / jdbc
```

没有反向依赖，也没有循环：`meta` 不依赖 `engine`，`engine` 不知道仓库；
`jdbc` 不知道注解，`assembly` 不知道连接。

### 四层职责

| 层 | 类 | 知道什么 | 不知道什么 | 变化频率 |
|---|---|---|---|---|
| 装配 | `RepositoryAssembler` → `Assembled` | 注解、资源 JSON、方言 | 连接、事务 | 启动一次 |
| 渲染 | `SqlV2` | 占位符、参数类型、分页 | 谁在调、连哪个库 | 改 SQL 时 |
| 生成 | `CrudSql` | 表名、列、主键、审计 | 连接、结果映射 | 改删除/批量语义时 |
| 执行 | `JdbcExecutor` | `DataSource`、`sql + args` | 实体、注解、SQL 从哪来 | 换连接池/加埋点时 |

`SqlJsonRepository` 只是把这四层接起来：方法名 → 语句名 → 参数 → 执行 → 结果。
它不拼 SQL、不碰 `PreparedStatement`、也不解析注解。

判断「这层有没有多干一件事」用一条标准：**把它单独 new 出来需要哪些依赖？**
`CrudSql` 只要 `EntityDescriptor`（纯反射，不需要库），`JdbcExecutor` 只要 `DataSource`
（不需要注解），`RepositoryAssembler` 只需要 classpath（不需要库）。
凡是「必须起数据库才能测」的逻辑，就说明它被放到错误的层了。

### 扩展默认语句：实现接口就行

默认六条不是硬编码的，`StatementFactory` 就是扩展点：

```java
public class CountAllStatement implements StatementFactory {
    @Override
    public String name() {
        return "countAll";
    }

    @Override
    public ObjectNode build(EntityDescriptor entity) {
        ObjectNode node = Statements.node();
        node.set("sql", Statements.sql("SELECT COUNT(*) AS cnt FROM " + entity.table()));
        return node;
    }
}
```

两种声明方式：

```java
// 1) 注解：跟着实体走，适合固定扩展
@SqlJson(value = "user", table = "t_user", statements = { CountAllStatement.class })
public class SysUser { ... }

// 2) 编程：运行期决定，适合按配置或多租户差异化
SqlJsonRepository.of(SysUser.class, ds, Dialect.mysql(), List.of(new CountAllStatement()));
```

同名即覆盖，且**保留原位置**——顺序稳定，日志和 `statements()` 对得上。

优先级从高到低：**resource 里的 JSON > 注解/参数声明的工厂 > 内置默认**。
把 JSON 放最高，是因为它能改配置、走发布流程；临时改一条 SQL 不该逼人重新编译。

**为什么不做 ServiceLoader 自动发现**：自动发现看着省事，但「我明明写了实现，为什么语句没生效」
会变成排查噩梦——类路径、`META-INF/services`、构造器可见性，任何一处错都静默失败。
显式声明错误时至少能看到「这个类没被引用」。

### 注释约定

- **类级**：做什么、为什么这么设计、线程安全、一个能跑的例子、已知的坑
- **方法级**：`@param` / `@return` / `@throws`，只写契约（空值、副作用、幂等），不复述方法名
- **行内**：解释「为什么」，不解释「是什么」
- **getter/setter 不写注释**：那是噪音，不是文档

`mvn -pl sqljson-core javadoc:javadoc` 可以生成完整文档，当前 0 error。

## 五、Demo：用户关系系统

Spring Boot 3 + 会话登录 + 角色 + 回收站，四个 tab：

- **用户**：姓名模糊 + 部门/状态筛选 + 分页 + 新建 + 删除（管理员）
- **关系**：建立/解除关系，不能自连、不能重复、目标必须存在
- **图谱**：圆形布局，节点大小=关系度数，实线好友/细线关注/虚线拉黑
- **回收站**：管理员可见，查看已删数据并恢复

分层是单向的：`web（搬数据）→ service（业务规则）→ repository（参数整理）→ core（执行）`。service 里没有 SQL 关键字，repository 里没有"能不能这么做"的判断。

## 六、测试

```
sqljson-core    12x   占位符/类型/区域/模板/分页/逻辑删除/审计/批量/方言/指标/join/分层
sqljson-spring    2   扫描装配、CRUD 与分页
sqljson-demo      7   登录、401/403、逻辑删除、回收站、恢复、业务规则
```

```bash
mvn -q test
```

## 七、踩过的坑（都是真问题）

**1. `SERVER__PORT` 会覆盖 `server.port`。** 某些环境（IDE / 沙箱）注入这个变量，Spring Boot 的宽松绑定把它当成 `server.port`，优先级高于 `application.yml`，表现为「启动成功但配置的端口打不开」。`sqljson-demo/run.sh` 里用 `env -u SERVER__PORT` 清掉。

**2. MySQL 首次连接做反向 DNS。** 在 DNS 受限的机器上，每个新连接会阻塞约 30 秒，看起来像「启动卡住且没日志」。缓解手段：`DbWarmer` 后台预热 + 放宽 `connection-timeout`；根治是给该 IP 配一条 hosts 记录，或在网络正常的机器上部署。

**3. 不加 `-parameters` 编译，`@RequestParam` 会报 "Name for argument not specified"。** 父 POM 已显式开启；同时所有注解都写了显式名字，双保险。

**4. 整实体注入按列名取属性，驼峰字段会静默丢值。** `user_name` 取不到 `userName`，插入成功但字段是 null，不报错。修复是列名 → 驼峰兜底查找。这类问题只有跑真实数据才暴露。

**5. 分页的 count 语句必须继承逻辑删除过滤。** 否则总数包含已删数据，翻页越翻越少。钩子挂在渲染阶段而不是分页封装阶段，就是为了这个。

## 八、边界

- 不做对象图（无懒加载、无级联、无一级/二级缓存）
- 不做 Wrapper 式运行时拼装 API
- `limit` 在 H2/MySQL/PG 可用，Oracle/达梦要走 `Dialect`
- 跨文件复用模板不支持，模板只在同一 JSON 内生效
- 登录是演示实现（固定盐 SHA-256），生产要换 BCrypt/Argon2 + CSRF 防护

判断一条新能力该不该加，只有一个标准：**它让 SQL 更容易被看见，还是更容易被隐藏？**
