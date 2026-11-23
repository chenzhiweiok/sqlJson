package io.github.jsonsql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.jsonsql.mapping.BeanMapper;
import io.github.jsonsql.mapping.JoinAssembler;
import io.github.jsonsql.support.SqlMetrics;

@DisplayName("第 2 批：指标、扁平 join 组装、字段缓存")
class MetricsAndJoinTest {

    @Nested
    @DisplayName("SQL 指纹")
    class Fingerprint {

        @Test
        @DisplayName("空白与数字归一，参数不同但形状相同的 SQL 会归并")
        void sameShape() {
            String a = "select id from t_user where age >= 18 order by id limit 10";
            String b = "SELECT   id\n  FROM t_user\n WHERE age >= 30 ORDER BY id LIMIT 20";
            assertEquals(SqlMetrics.fingerprint(a), SqlMetrics.fingerprint(b));
        }

        @Test
        @DisplayName("字符串字面量也算参数")
        void quoted() {
            String a = "select * from t where name = 'a' and status = 'A'";
            String b = "select * from t where name = 'b' and status = 'B'";
            assertEquals(SqlMetrics.fingerprint(a), SqlMetrics.fingerprint(b));
        }

        @Test
        @DisplayName("排序字段不同应视为不同指纹")
        void identDiffers() {
            String a = "select * from t order by age";
            String b = "select * from t order by id";
            assertNotEquals(SqlMetrics.fingerprint(a), SqlMetrics.fingerprint(b));
        }

        @Test
        @DisplayName("表名里的数字不应被吃掉")
        void tableWithDigit() {
            assertTrue(SqlMetrics.fingerprint("select * from t_user1").contains("t_user1"));
        }
    }

    @Nested
    @DisplayName("指标统计")
    class Metrics {

        @Test
        @DisplayName("按总耗时排序，能算出均值与 P99")
        void collect() {
            SqlMetrics m = SqlMetrics.create();
            String sql = "select * from t_user where id = 1";

            m.record("user.findById", sql, 10, 1, true);
            m.record("user.findById", sql, 30, 1, true);
            m.record("user.findById", sql, 500, 1, true);
            m.record("user.findById", sql, 5, 0, false);
            m.record("user.list", "select * from t_user", 100, 10, true);

            assertEquals(2, m.summary().statements());
            assertEquals(5, m.summary().calls());
            assertEquals(1, m.summary().errors());
            assertEquals(1, m.summary().slowCalls(), "500ms 那条应计入慢查询");

            SqlMetrics.Stat top = m.topByTotal(1).get(0);
            assertEquals("user.findById", top.stmt());
            assertEquals(545, top.totalMs());
            assertEquals(500, top.maxMs());
            assertEquals(0, top.lastRows(), "最后一次执行返回 0 行");
        }

        @Test
        @DisplayName("样本满了以后 P99 不会越界")
        void ringBuffer() {
            SqlMetrics m = SqlMetrics.create();
            for (int i = 0; i < 2000; i++) {
                m.record("x", "select 1 from dual", i, 1, true);
            }
            long p99 = m.topByP99(1).get(0).p99Ms();
            assertTrue(p99 >= 1970 && p99 <= 1999, "P99 应落在 2000 个样本的后 1%，实际 " + p99);
        }

        @Test
        @DisplayName("reset 清空")
        void reset() {
            SqlMetrics m = SqlMetrics.create();
            m.record("x", "select 1", 1, 1, true);
            m.reset();
            assertEquals(0, m.summary().statements());
        }
    }

    @Nested
    @DisplayName("扁平 join 组装")
    class Join {

        public static class UserView {
            public Long id;
            public String userName;
            public List<RelView> relations;
        }

        public static class RelView {
            public Long id;
            public String relType;
            public Long targetId;
        }

        @Test
        @DisplayName("一个主对象带多个子对象，重复行自动去重")
        void groupOneToMany() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row(1L, "邱老师", 10L, "FRIEND", 2L));
            rows.add(row(1L, "邱老师", 11L, "FOLLOW", 3L));
            rows.add(row(2L, "张三", 12L, "FOLLOW", 1L));

            List<UserView> users = JoinAssembler.of(rows, "id", UserView.class)
                    .many("relations", RelView.class, "rel_id", "rel_")
                    .assemble();

            assertEquals(2, users.size());
            assertEquals("邱老师", users.get(0).userName);
            assertEquals(2, users.get(0).relations.size(), "两条关系都应挂上");
            assertEquals("FRIEND", users.get(0).relations.get(0).relType);
            assertEquals(3L, users.get(0).relations.get(1).targetId);
            assertEquals(1, users.get(1).relations.size());
        }

        @Test
        @DisplayName("子表无数据时给空集合，而不是 null")
        void emptyChild() {
            List<Map<String, Object>> rows = List.of(row(3L, "李四", null, null, null));
            List<UserView> users = JoinAssembler.of(rows, "id", UserView.class)
                    .many("relations", RelView.class, "rel_id", "rel_")
                    .assemble();

            assertEquals(1, users.size());
            assertTrue(users.get(0).relations.isEmpty(), "join 到空行时子集合应为空列表");
        }

        @Test
        @DisplayName("重复子行只保留一条（join 放大后的去重）")
        void dedupeChild() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row(1L, "邱老师", 10L, "FRIEND", 2L));
            rows.add(row(1L, "邱老师", 10L, "FRIEND", 2L));

            List<UserView> users = JoinAssembler.of(rows, "id", UserView.class)
                    .many("relations", RelView.class, "rel_id", "rel_")
                    .assemble();

            assertEquals(1, users.size(), "两行同一个主键，应合成一个主对象");
            assertEquals(1, users.get(0).relations.size(), "同一 rel_id 只应保留一次");
        }

        @Test
        @DisplayName("统计信息能看出放大倍数")
        void stats() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row(1L, "邱老师", 10L, "FRIEND", 2L));
            rows.add(row(1L, "邱老师", 11L, "FOLLOW", 3L));
            rows.add(row(2L, "张三", 12L, "FOLLOW", 1L));

            JoinAssembler<UserView> asm = JoinAssembler.of(rows, "id", UserView.class)
                    .many("relations", RelView.class, "rel_id", "rel_");
            asm.assemble();

            assertEquals(3, asm.stats().rows());
            assertEquals(2, asm.stats().roots());
            assertEquals(3, asm.stats().children());
            assertEquals(1.5, asm.stats().fanOut());
        }

        private Map<String, Object> row(Long id, String name, Long relId, String relType, Long targetId) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("user_name", name);
            m.put("rel_id", relId);
            m.put("rel_type", relType);
            m.put("rel_target_id", targetId);
            return m;
        }
    }

    @Nested
    @DisplayName("BeanMapper")
    class Mapping {

        public static class Bean {
            public Long id;
            public String userName;
            public Integer age;
            public boolean active;
        }

        @Test
        @DisplayName("下划线列名映射到驼峰字段，并做类型转换")
        void snakeToCamel() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ID", 1L);
            row.put("user_name", "邱老师");
            row.put("age", "32");
            row.put("active", 1);

            Bean b = BeanMapper.fromMap(row, Bean.class);
            assertEquals(1L, b.id);
            assertEquals("邱老师", b.userName);
            assertEquals(32, b.age);
        }

        @Test
        @DisplayName("多余的列静默忽略（join 结果常见）")
        void ignoreUnknownColumn() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", 5L);
            row.put("not_a_field", "x");
            assertNull(BeanMapper.fromMap(row, Bean.class).userName);
            assertEquals(5L, BeanMapper.fromMap(row, Bean.class).id);
        }
    }
}
