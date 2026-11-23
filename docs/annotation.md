# 实体注解：一个 @SqlJson 换一套 CRUD

```java
@SqlJson(value = "user", table = "t_user", resource = "sqls/user-complex.json")
public static final class User {
    @SqlColumn(id = true, generated = true)
    public Long id;

    @SqlColumn("user_name")
    public String userName;

    public Integer age;
    public BigDecimal balance;
    public String status;
}
```

```java
SqlJsonRepository<User> repo = SqlJsonRepository.of(User.class, dataSource);

long id = repo.insert(new User("邱老师", 32, "1200.50", "A"));  // 主键自动回填
User u = repo.findById(id);
PageResult<User> page = repo.findPage(1, 10);
repo.updateById(patch);      // 只更新有值字段
repo.deleteById(id);
```

实体不再需要写一行 SQL，也不再需要单独的 `user.json`——**默认语句是运行时按注解生成的**。

## 注解

| 注解 | 位置 | 作用 |
|---|---|---|
| `@SqlJson` | 类 | `value` 命名空间、`table` 表名（留空则按命名空间转下划线）、`resource` 复杂 SQL、`idColumn` 默认 `id` |
| `@SqlColumn` | 字段 | `value` 列名、`id` 主键、`generated` 自增、`skip` 不参与映射 |

不标 `@SqlColumn` 的字段：字段名转下划线当列名（`userName` → `user_name`），名为 `id` 的字段自动当主键。

## 默认生成六个语句

| 语句 | 生成的 SQL（编译后） |
|---|---|
| `insert` | `INSERT INTO t_user (user_name, age, balance, status) VALUES (?, ?, ?, ?)` |
| `updateById` | `UPDATE t_user SET user_name = ?, age = ?, balance = ?, status = ? WHERE id = ?` |
| `deleteById` | `DELETE FROM t_user WHERE id = ?` |
| `findById` | `SELECT id, user_name, age, balance, status FROM t_user WHERE id = ?` |
| `findAll` | `SELECT ... FROM t_user ORDER BY id ASC` |
| `findPage` | `SELECT ... ORDER BY id ASC` + `pagination: {style: page}` |

上面是 `explain()` 打出来的真实结果，不是示意。

### 关键设计：默认语句本身也是 JSON

注解不是硬编码 SQL 字符串，而是**运行时生成一份 v2 格式的 JSON**，再交给同一个 `SqlV2` 编译：

```
@SqlJson 注解 ──反射──▶ ObjectNode（默认 CRUD 的 JSON）──合并资源──▶ SqlV2 ──▶ SQL + args
```

好处很实在：注解生成的语句和手写语句走**完全相同**的编译路径。占位符规则、`ident` 白名单、分页推导，一套逻辑，不会出现"注解版行为和 JSON 版不一样"这种分裂。调试手段也一样——`repo.explain()` 出来的就是 JSON 里那套东西。

## 资源覆盖：默认只补缺

`resource` 指向的 JSON 里，**同名语句优先，默认语句只在缺失时补上**。

所以想给默认 `findPage` 加过滤条件，不用改注解，只要在资源里重写 `findPage`：

```json
{
  "user": {
    "findPage": {
      "sql": [
        "SELECT id, user_name, age, balance, status",
        "  FROM t_user",
        " WHERE 1=1",
        "   AND (#1 IS NULL OR status = #1)   -- [1] string status 可选状态",
        " ORDER BY id ASC"
      ],
      "pagination": { "style": "page", "pageParam": "pageNo", "sizeParam": "pageSize" }
    }
  }
}
```

覆盖后的实际编译结果：

```
SELECT id, user_name, age, balance, status FROM t_user
 WHERE 1=1 AND (? IS NULL OR status = ?) ORDER BY id ASC   -- args=[null, null]
```

不传 status 就是全量，传了就过滤——`(#n IS NULL OR col = #n)` 这个写法让"可选条件"不需要额外语法。

## 复杂 SQL 也挂在这个实体下

```json
"search": {
  "sql": [
    "SELECT id, user_name, age, balance, status",
    "  FROM t_user",
    " WHERE 1=1",
    "   AND (#1 IS NULL OR user_name LIKE #1)   -- [1] string keyword 关键字",
    "   AND (#2 IS NULL OR age >= #2)           -- [2] int    minAge   最小年龄",
    " ORDER BY id ASC"
  ],
  "pagination": { "style": "page", "pageParam": "pageNo", "sizeParam": "pageSize" }
},
"countByStatus": {
  "sql": ["SELECT status, COUNT(*) AS cnt", "  FROM t_user", " GROUP BY status", " ORDER BY status"],
  "pagination": {
    "style": "offset",
    "countSql": "SELECT COUNT(*) FROM (SELECT status FROM t_user GROUP BY status) t"
  }
}
```

调用：

```java
PageResult<User> r = repo.page("search", 1, 10, Map.of("keyword", "%张%"));
PageResult<Map<String,Object>> stat = repo.pageMaps("countByStatus", 1, 10, Map.of());
```

## Repository API

| 方法 | 说明 |
|---|---|
| `insert(T)` | 返回主键，自增时回填到实体 |
| `updateById(T)` | 只更新非 null 字段 |
| `deleteById(Object)` | 物理删除 |
| `findById(Object)` / `findAll()` | 主键查 / 全量 |
| `findPage(pageNo, pageSize)` | 默认分页 |
| `page(stmt, pageNo, size, extra)` | 任意语句分页，返回实体 |
| `pageMaps(...)` / `queryMaps(...)` | 返回 `Map`，用于聚合、报表 |
| `query(stmt, args)` | 任意语句查实体列表 |
| `explain(stmt, args)` | 看编译后的 SQL，调优用 |
| `statements()` | 列出这个实体可用的全部语句名 |

## 实测输出

```
实体 User 可用语句: [findPage, search, countByStatus, insert, updateById, deleteById, findById, findAll]

=== 1. 插入 ===
  回填主键: 1  ->  User{id=1, name=邱老师, age=32, balance=1200.50, status=A}

=== 2. findById ===
  User{id=1, name=邱老师, age=32, balance=1200.50, status=A}

=== 4. findPage(1, 2) —— 用的是资源里覆盖的版本 ===
  User{id=1, name=邱老师, ...}
  User{id=2, name=张三, ...}
  PageResult{本页=2, total=4, pageNo=1, pageSize=2, pages=2}

=== 5. search(keyword=%张%) ===
  User{id=2, name=张三, ...}
  PageResult{本页=1, total=1, pageNo=1, pageSize=10, pages=1}

=== 6. updateById（只改 balance）===
  影响行数: 1
  更新后: User{id=1, name=邱老师, age=32, balance=8888.88, status=A}   ← 姓名没被 null 覆盖

=== 7. countByStatus（聚合分页）===
  {status=A, cnt=3}
  {status=B, cnt=1}
  PageResult{本页=2, total=2, pageNo=1, pageSize=10, pages=1}

=== 8. deleteById ===
  影响行数: 1
  剩余: 3 条
```

第 6 条值得注意：只传 `id` 和 `balance` 的 patch 对象，生成的 SQL 只有 `SET balance = ?`，**没传的字段不会被写成 null**。这是"只注入有值字段"的直接好处。

## 局限（写在明面上）

1. **只做单表。** 关联查询、多表 join 一律走资源里的复杂 SQL，注解不管。
2. **没有事务封装。** 需要多步原子操作时自己包 `Connection`，或后续给 repository 加 `tx(Runnable)`。
3. **条件不能传对象。** 不能像 v1 那样传 `where: {age: {$gte: 18}}`，只能靠资源覆盖 `findPage` 或新写语句。这是 v2"只认 SQL"的代价。
4. **自增主键回填依赖 `getGeneratedKeys()`**，个别老驱动不支持，会返回 0。
5. **没有缓存、没有逻辑删除、没有审计字段自动填充。**

## 建议的下一步

按投入产出排：

1. **`tx(Runnable)`** —— 事务是最缺的一块，几十行。
2. **逻辑删除** —— `@SqlColumn(deleted = true)`，默认查询自动加 `deleted = 0`。
3. **审计字段** —— `createdAt / updatedAt` 自动填充，省掉每个 service 里重复赋值。
4. **Kotlin data class / Java record 支持** —— 现在依赖无参构造 + 反射赋值。

前三项加起来不到两百行，就能把一个"能跑"的仓库变成"能用"的仓库。
