# JSON SQL 格式 v2 · SQL 原文可见，描述写在旁边

v2 的目标只有一个：**打开 JSON 就能看见 SQL 原样长什么样，占位符的意思就写在它旁边。**

```json
"findPage": {
  "sql": [
    "SELECT id, user_name, age, balance, status",
    "  FROM t_user",
    " WHERE status = #1                    -- [1] string status    状态 A/B",
    "   AND age BETWEEN #2 AND #3          -- [2] int    minAge    最小年龄",
    "                                      -- [3] int    maxAge    最大年龄",
    " ORDER BY #4                          -- [4] ident  sortField 排序字段",
    " LIMIT #5                             -- [5] raw    limit     返回条数"
  ],
  "params": [
    { "index": 4, "enum": ["age", "id", "balance"] }
  ]
}
```

三件事同时成立：SQL 原样可读、占位符带上类型和说明、解析器只认 SQL 不认别的。

## 为什么 `sql` 用数组而不是带 `\n` 的字符串

JSON 字符串不能换行，写成 `"SELECT ...\n  FROM ..."` 满屏反斜杠，根本读不下去。
用**字符串数组**，一行一个元素，解析时 `join("\n")`——文件里看到的就是对齐好的 SQL。

## 占位符全表

| 写法 | 名称 | 语义 | 渲染成 |
|---|---|---|---|
| `#1` `#2` `#n` | 位置占位 | 对应 `params` 里第 n 项的值 | `?` |
| `#{item}` | 整实体 | 按所在位置展开（见下表） | 列名 / `?,?,?` / 赋值串 |
| `#{item1}` `#{item2}` | 多实体 | 各注入各自的对象 | 同上 |
| `#{item.age}` | 实体属性 | 取对象字段 | `?` |
| `#{age}` | 命名参数 | 普通参数，可直接传值 | `?` |
| `#{a.b}` | 嵌套参数 | `a.b` 逐层取 | `?` |

**v1 的 `${}` 在 v2 里取消了**，改由 `type: "ident"` 承担。这一步是刻意的：
把"这里要拼标识符"从**语法**（`${}`）挪到**描述**（`type`），于是安全策略跟着描述走，
`params` 里一眼就能审出哪些位置允许拼接，而不是靠在 SQL 里找 `${`。

### `#{item}` 的展开取决于它落在哪

| 出现位置 | 展开结果 |
|---|---|
| `INSERT INTO t ( ... )` 列区 | `id, user_name, age` |
| `VALUES ( ... )` 值区 | `?, ?, ?` |
| `UPDATE ... SET ... WHERE` 的 SET 区 | `user_name = ?, age = ?` |
| 其他位置 | 直接报错，不猜 |

```json
"insert": {
  "columns": ["id", "user_name", "age", "balance", "status"],
  "sql": [
    "INSERT INTO t_user",
    "       (#{item})",
    "VALUES (#{item})"
  ]
}
```

同一个 `#{item}` 出现两次，一次出列名、一次出占位符——因为解析器按**位置**判断，不按顺序。

**只注入有值的字段**：没传的字段不会出现在列里。所以下面这条 update，传 `{balance: 8888.88}` 就只改 `balance`：

```json
"updateById": {
  "columns": ["user_name", "age", "balance", "status"],
  "sql": [
    "UPDATE t_user",
    "   SET #{item}",
    " WHERE id = #1           -- [1] long id 主键"
  ]
}
```

```sql
UPDATE t_user SET balance = ? WHERE id = ?
```

## 描述可以写在三个地方

| 位置 | 写法 | 适合 |
|---|---|---|
| SQL 行尾注释 | `-- [1] string status 状态 A/B` | 人读，跟 SQL 贴在一起 |
| `params` 数组 | `{"index":4,"enum":[...]}` | 机器用，补充枚举、必填、默认值 |
| 两处并用 | 注释给全量，`params` 只补差异 | 推荐 |

解析顺序：**先读行内注释建表，再用 `params` 覆盖**。所以 `params` 里只写 `{ "index": 4, "enum": [...] }` 就够了，不用把类型和说明重抄一遍。

## 类型（`type`）

| type | 转换 | 渲染 |
|---|---|---|
| `string`（默认） | `String.valueOf` | `?` |
| `int` / `long` | 转整数，`"18"` → `18` | `?` |
| `decimal` | 转 `BigDecimal` | `?` |
| `bool` | 转布尔 | `?` |
| `datetime` / `date` | 转 `LocalDateTime` / `LocalDate` | `?` |
| `ident` | 正则 `[A-Za-z_][A-Za-z0-9_]*`，可选 enum 白名单 | **原样拼接** |
| `raw` | 只接受整数（`^-?\d+$`） | **原样拼接** |

`ident` 和 `raw` 是仅有的两个拼接出口，也是仅有的两个能改变 SQL 结构的类型。
其余全部参数化。`raw` 限定整数，是为了让 `LIMIT` 能用而不至于变成注入口。

## 优先级：字段描述 > 实体推断

用户显式写下的描述，永远压过引擎的运行时判断：

1. **完整表达式描述**：`{"name":"item2.status","enum":["A","B"]}` —— 精确命中，最高
2. **同名字段描述**：`{"name":"status","type":"string"}`
3. **实体推断**：都没写，就按 `item` 里那个值的实际运行时类型走

```json
"copyFields": {
  "sql": ["INSERT INTO t_user (user_name, age, status)",
          "VALUES (#{item1.user_name}, #{item1.age}, #{item2.status})"],
  "params": [
    { "name": "item1.user_name", "type": "string" },
    { "name": "item1.age",       "type": "int" },
    { "name": "item2.status",    "type": "string", "enum": ["A", "B"] }
  ]
}
```

传 `item2.status = "X"` → 直接拒绝，因为字段级描述里限定了 A/B。

## 实测输出（9 个场景）

```
=== 解析出的参数描述（来自行内注释）===
  #1  type=string  name=status     desc=状态 A/B
  #2  type=int     name=minAge     desc=最小年龄
  #3  type=int     name=maxAge     desc=最大年龄
  #4  type=ident   name=sortField  desc=排序字段  enum=[age, id, balance]
  #5  type=raw     name=limit      desc=返回条数

1) user.insert   (#{item} 出现两次)
  SQL : INSERT INTO t_user (id, user_name, age, balance, status) VALUES (?, ?, ?, ?, ?)
  ARGS: [100, 邱老师, 32, 1200.5, A]

2) user.insertExplicit (#1 #2 #3)
  SQL : INSERT INTO t_user (id, user_name, age) VALUES (?, ?, ?)
  ARGS: [200, 张三, 28]

3) user.updateById (#{item} 在 SET 区)
  SQL : UPDATE t_user SET balance = ? WHERE id = ?
  ARGS: [8888.88, 100]

4) user.findPage (#1..#5，minAge 故意传字符串 "18")
  SQL : SELECT ... FROM t_user WHERE status = ? AND age BETWEEN ? AND ?
        ORDER BY age LIMIT 10
  ARGS: [A, 18, 60]          ← 字符串按描述转成了 int

5) user.copyFields (item1 / item2)
  SQL : INSERT INTO t_user (user_name, age, status) VALUES (?, ?, ?)
  ARGS: [李四, 17, B]

6) 排序字段注入
  已拦截: sortField=age; drop table t_user 不在白名单 [age, id, balance]
  已拦截: sortField=password 不在白名单 [age, id, balance]

7) 字段级描述优先（item2.status 只允许 A/B）
  已拦截: item2.status=X 不在白名单 [A, B]

8) limit 传非整数
  已拦截: raw 只接受整数，收到 10; drop table t_user
```

## 与 v1 的差异

| | v1 | v2 |
|---|---|---|
| SQL 存储 | 单个字符串，`\n` 转义 | **字符串数组**，一行一元素 |
| 参数描述 | 独立的 `params` 数组，与 SQL 分离 | **行内注释 + `params` 合并**，就写在 SQL 旁边 |
| 标识符 | `${ident}` 语法 | `type: "ident"`，跟着描述走 |
| 整实体 | `#{item.columns}` / `#{item.values}` 两个宏 | **一个 `#{item}`**，按位置自动判断 |
| 多实体 | 只支持 `items` 批量 | `#{item1}` / `#{item2}` 任意多个 |
| 结构模式 | `table` + `where` 生成 SQL | 取消，v2 只认 SQL 原文 |

v2 是有意"退化"成只认 SQL 的：**一条语句就是一坨可读的 SQL + 一份参数说明**，
不再有"省略 SQL 由引擎拼"的分支。分支少了，行为就确定了。

v1 的 `SqlCompiler` 仍然保留，两套并存：新语句用 v2，历史语句不用改。

## 落地建议

1. **一个业务一个文件**，命名空间与文件名一致，语句按 `insert / updateById / findPage` 排序，diff 才好看。
2. **注释里的 `[n]` 必须连续**，且与 SQL 里的 `#n` 一一对应——加一个参数忘了改编号，是这套格式最容易犯的错。可以加个 lint 规则检查。
3. **`ident` / `raw` 的位置逐个 review**，它们是唯一的拼接口。
4. `columns` 建议显式写出来（而不是靠实体字段顺序推断），否则字段顺序一变，SQL 就悄悄跟着变。

---

# 模板与分页

## 一、模板：一次定义，多表复用

```json
"templates": {
  "findPage": {
    "description": "通用分页查询",
    "sql": [
      "SELECT {{columns}}",
      "  FROM {{table}}",
      " WHERE 1=1"
    ],
    "pagination": { "style": "offset", "sizeParam": "pageSize", "offsetParam": "offset" }
  }
}
```

**两套括号，分工明确：**

| 语法 | 时机 | 作用 |
|---|---|---|
| `{{var}}` | 编译期（加载时替换） | 决定**这条 SQL 长什么样** |
| `#{expr}` | 运行期（每次查询） | 决定**这次传什么值** |

`{{}}` 写错会在加载阶段就报错（`模板变量 {{columns}} 未定义`），不会拖到线上才炸。

### 引用与扩展

```json
"user.findPage": {
  "template": "findPage",
  "vars": { "table": "t_user", "columns": "id, user_name, age, balance, status" },
  "sql": [
    "   AND status = #1        -- [1] string status 状态 A/B",
    "   AND age >= #2         -- [2] int    minAge 最小年龄",
    " ORDER BY id ASC"
  ]
}
```

语句自己的 `sql` 会**追加**在模板展开结果之后——模板给骨架，语句补条件。这就是"可自定义扩展"的落点：通用部分进模板，差异部分留在语句里。

同一模板换个表：

```json
"order.findPage": {
  "template": "findPage",
  "vars": { "table": "t_order", "columns": "id, order_no, amount, status" },
  "sql": ["   AND status = #1        -- [1] string status 订单状态", " ORDER BY id ASC"]
}
```

合并规则（**语句永远赢**）：

| 字段 | 规则 |
|---|---|
| `vars` | 模板默认值 → 语句覆盖 |
| `params` | 模板先应用 → 语句按 index 覆盖，缺的字段继承模板 |
| `columns` | 语句有就用语句的，否则用模板的 |
| `pagination` | 语句有就用语句的，否则继承模板 |
| `sql` | 模板在前，语句追加在后 |

## 二、分页

### 三种风格

| `style` | 调用方传 | offset 怎么来 |
|---|---|---|
| `offset`（默认） | `offset` + `pageSize` | 直接用 |
| `page` | `pageNo` + `pageSize` | `(pageNo - 1) * size` |
| `keyset` | 游标（SQL 自己写 `WHERE id > #{lastId}`） | 引擎只补 limit |

```json
"pagination": { "style": "page", "pageParam": "pageNo", "sizeParam": "pageSize" }
```

分页参数是**执行层参数**，不进 SQL。所以 `pageSize` 不会变成 `?`，也就不受 SQL 占位符个数限制。

### count 语句从哪来

| 情况 | 行为 |
|---|---|
| 只写 `pagination` | **自动推导**：把 `SELECT ...` 换成 `SELECT COUNT(*)`，去掉 `ORDER BY` |
| 写了 `pagination.countSql` | 用它，支持 `{{}}` 和 `#{}` |
| SQL 含 `GROUP BY` / `DISTINCT` | 拒绝推导并报错，要求显式写 countSql |

自动推导比手写稳：手写两份 WHERE，改条件时忘改一处，分页数就会悄悄不对。

### 分页子句谁来加

SQL 里**没有** `LIMIT` / `FETCH FIRST` → 引擎追加 `LIMIT n OFFSET m`；
自己写了就不动，交给语句控制（换方言时用这个口子）。

### 返回什么

```java
public record PagePrepared(String listSql, List<Object> listArgs,
                           String countSql, List<Object> countArgs,
                           int pageNo, int pageSize) {}
```

一次给两条 SQL、两份已对齐的参数，执行层只管跑。不把执行器绑进引擎，方便接 JdbcTemplate / MyBatis / R2DBC。

### 实测

```
1) user.findPage  第 1 页（pageSize=2, offset=0）
  LIST : SELECT id, user_name, age, balance, status FROM t_user
         WHERE 1=1 AND status = ? AND age >= ? ORDER BY id ASC LIMIT 2
  COUNT: SELECT COUNT(*) FROM t_user WHERE 1=1 AND status = ? AND age >= ?
  -> 1, 邱老师, 32, 1200.50, A
  -> 2, 张三, 28, 300.00, A
  本页 2 条 / 共 4 条 / 第 1 页 / 共 2 页

2) user.findPage  第 2 页（offset=2）
  LIST : ... ORDER BY id ASC LIMIT 2 OFFSET 2
  -> 3, 李四, 17, 80.00, A
  -> 5, 赵六, 51, 50.00, A
  本页 2 条 / 共 4 条 / 第 2 页 / 共 2 页

3) user.findByPageNo  第 2 页（pageNo=2, pageSize=3）
  LIST : ... ORDER BY age DESC LIMIT 3 OFFSET 3
  本页 1 条 / 共 4 条 / 第 2 页 / 共 2 页

4) order.findPage（复用同一模板）
  LIST : SELECT id, order_no, amount, status FROM t_order
         WHERE 1=1 AND status = ? ORDER BY id ASC LIMIT 10
  -> 1, NO-001, 99.00, PAID
  -> 3, NO-003, 299.00, PAID
  本页 2 条 / 共 2 条 / 第 1 页 / 共 1 页

6) 模板变量缺失
  已拦截: x.y: 模板变量 {{columns}} 未定义

7) GROUP BY 无法自动 count
  已拦截: 含 GROUP BY / DISTINCT 的语句无法自动推导 count，请在 pagination.countSql 里显式指定
```

## 三、三个坑

1. **深页 offset 会慢。** `OFFSET 100000` 要求数据库先扫十万行。数据量大时改 `style: keyset`，用 `WHERE id > #{lastId} ORDER BY id LIMIT #{size}`，代价是不能跳页。
2. **countSql 不要手写第二份 WHERE。** 除非必须（GROUP BY、多表聚合），一律让引擎从主 SQL 推导，否则条件一改就对不上。
3. **跨文件复用模板目前不支持。** 模板只在同一个 JSON 文件内生效。要多文件共用，就在构建期合并成一份，或者把公共模板单独发布成一个文件由上层 include——后者还没实现。

## 四、运行

```bash
mvn -q -DskipTests compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" io.github.jsonsql.Demo3
```
