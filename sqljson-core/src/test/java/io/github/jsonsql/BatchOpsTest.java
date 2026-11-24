package io.github.jsonsql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("批量能力：批量删除 / 恢复 / IN 查询 / 同值更新 / 游标分页")
class BatchOpsTest {

    private JdbcDataSource ds;
    private SqlJsonRepository<TestEntities.SoftUser> soft;
    private SqlJsonRepository<TestEntities.User> users;

    @BeforeEach
    void setUp() {
        ds = TestDb.fresh();
        soft = SqlJsonRepository.of(TestEntities.SoftUser.class, ds);
        users = SqlJsonRepository.of(TestEntities.User.class, ds);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Nested
    @DisplayName("批量删除")
    class Delete {

        @Test
        @DisplayName("软删：数据进回收站，重复删除幂等")
        void softDeleteBatch() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            long b = soft.insert(new TestEntities.SoftUser("乙"));
            long c = soft.insert(new TestEntities.SoftUser("丙"));

            assertEquals(2, soft.deleteBatch(List.of(a, b)));
            assertEquals(1, soft.findAll().size());
            assertEquals(2, soft.findDeleted(10).size(), "两条都应进回收站");

            assertEquals(0, soft.deleteBatch(List.of(a, b)), "已删的再删一次应影响 0 行");
            assertEquals(1, soft.findAll().size());
            assertNotNull(soft.findById(c));
        }

        @Test
        @DisplayName("无软删列的实体走物理删除")
        void physicalDeleteBatch() {
            long a = users.insert(new TestEntities.User("张三", 20, "0", "A"));
            long b = users.insert(new TestEntities.User("李四", 21, "0", "A"));

            assertEquals(2, users.deleteBatch(List.of(a, b)));
            assertEquals(0, users.findAll().size());
        }

        @Test
        @DisplayName("重复 id 去重后只影响一次")
        void deduplicatesIds() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            long b = soft.insert(new TestEntities.SoftUser("乙"));

            assertEquals(2, soft.deleteBatch(List.of(a, a, b, b)), "去重后应是 2 行");
        }

        @Test
        @DisplayName("空集合不访问数据库，直接返回 0")
        void emptyIsNoop() {
            soft.insert(new TestEntities.SoftUser("甲"));
            assertEquals(0, soft.deleteBatch(List.of()));
            assertEquals(0, soft.deleteBatch(null));
            assertEquals(1, soft.findAll().size());
        }
    }

    @Nested
    @DisplayName("批量恢复")
    class Restore {

        @Test
        @DisplayName("回收站批量恢复")
        void restoreBatch() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            long b = soft.insert(new TestEntities.SoftUser("乙"));
            long c = soft.insert(new TestEntities.SoftUser("丙"));
            soft.deleteBatch(List.of(a, b));

            assertEquals(2, soft.restoreBatch(List.of(a, b)));
            assertEquals(3, soft.findAll().size());
            assertEquals(0, soft.findDeleted(10).size());
            assertNotNull(soft.findById(c));
        }

        @Test
        @DisplayName("没有软删列的实体不能恢复")
        void requiresSoftDelete() {
            assertThrows(SqlSpecException.class, () -> users.restoreBatch(List.of(1L)));
        }
    }

    @Nested
    @DisplayName("按 ID 列表查询")
    class FindByIds {

        @Test
        @DisplayName("返回顺序等于传入顺序")
        void keepsInputOrder() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            long b = soft.insert(new TestEntities.SoftUser("乙"));
            long c = soft.insert(new TestEntities.SoftUser("丙"));

            List<TestEntities.SoftUser> list = soft.findByIds(List.of(c, a, b));
            assertEquals(3, list.size());
            assertEquals(c, list.get(0).id);
            assertEquals(a, list.get(1).id);
            assertEquals(b, list.get(2).id);
        }

        @Test
        @DisplayName("不存在的 id 直接跳过，不占位")
        void skipsMissing() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            List<TestEntities.SoftUser> list = soft.findByIds(List.of(a, 9999L));
            assertEquals(1, list.size());
            assertEquals(a, list.get(0).id);
        }

        @Test
        @DisplayName("已逻辑删除的查不到")
        void excludesDeleted() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            soft.deleteById(a);
            assertEquals(0, soft.findByIds(List.of(a)).size());
        }

        @Test
        @DisplayName("空集合返回空列表")
        void emptyReturnsEmpty() {
            assertTrue(soft.findByIds(List.of()).isEmpty());
            assertTrue(soft.findByIds(null).isEmpty());
        }

        @Test
        @DisplayName("超过分块大小也能正确合并")
        void crossesChunkBoundary() {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 510; i++) {
                ids.add(users.insert(new TestEntities.User("u" + i, 20, "0", "A")));
            }
            List<TestEntities.User> list = users.findByIds(ids);
            assertEquals(510, list.size(), "应跨块合并，不能只返回第一块");
            assertEquals(ids.get(0), list.get(0).id);
            assertEquals(ids.get(509), list.get(509).id);
        }
    }

    @Nested
    @DisplayName("批量同值更新")
    class UpdateFields {

        @Test
        @DisplayName("一批行改成同一个值，并刷新审计字段")
        void updatesSameValue() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            long b = soft.insert(new TestEntities.SoftUser("乙"));
            soft.insert(new TestEntities.SoftUser("丙"));

            assertEquals(2, soft.updateField("userName", "已封禁", List.of(a, b)));

            TestEntities.SoftUser loaded = soft.findById(a);
            assertEquals("已封禁", loaded.userName);
            assertNotNull(loaded.updatedAt, "审计字段应被刷新");
            assertEquals(1, soft.countBy("userName", "丙"), "第三条不应被改");
        }

        @Test
        @DisplayName("主键与逻辑删除列不允许改")
        void rejectsProtectedColumns() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            assertThrows(SqlSpecException.class, () -> soft.updateField("id", 100L, List.of(a)));
            assertThrows(SqlSpecException.class, () -> soft.updateField("deleted", 0, List.of(a)));
        }

        @Test
        @DisplayName("未知字段与空 Map 都拒绝")
        void rejectsBadInput() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            assertThrows(SqlSpecException.class, () -> soft.updateField("nickname", "x", List.of(a)));
            assertThrows(SqlSpecException.class, () -> soft.updateFields(Map.of(), List.of(a)));
        }

        @Test
        @DisplayName("已删数据不会被批量更新复活")
        void excludesDeletedRows() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            soft.deleteById(a);
            assertEquals(0, soft.updateField("userName", "x", List.of(a)));
        }
    }

    @Nested
    @DisplayName("游标分页")
    class Cursor {

        @Test
        @DisplayName("按 id 游标扫完全表，每页不超上限")
        void scansAll() {
            for (int i = 0; i < 5; i++) {
                users.insert(new TestEntities.User("u" + i, 20, "0", "A"));
            }
            List<Long> seen = new ArrayList<>();
            long cursor = 0;
            while (true) {
                List<TestEntities.User> page = users.findAfter(cursor, 2);
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(u -> seen.add(u.id));
                cursor = page.get(page.size() - 1).id;
            }
            assertEquals(5, seen.size());
            assertEquals(seen.stream().sorted().toList(), seen, "应按主键升序");
        }

        @Test
        @DisplayName("跳过已删数据后仍能扫完，不会因不足一页而截断")
        void continuesAfterDeletedRows() {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(soft.insert(new TestEntities.SoftUser("u" + i)));
            }
            soft.deleteBatch(List.of(ids.get(1), ids.get(2)));

            List<Long> seen = new ArrayList<>();
            long cursor = 0;
            while (true) {
                List<TestEntities.SoftUser> page = soft.findAfter(cursor, 2);
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(u -> seen.add(u.id));
                cursor = page.get(page.size() - 1).id;
            }
            assertEquals(3, seen.size(), "5 条删 2 条应剩 3 条，且不能提前结束");
        }
    }

    @Nested
    @DisplayName("流式读取")
    class ForEach {

        @Test
        @DisplayName("逐行回调，返回消费行数")
        void consumesAll() {
            for (int i = 0; i < 3; i++) {
                soft.insert(new TestEntities.SoftUser("u" + i));
            }
            StringBuilder sb = new StringBuilder();
            int rows = soft.forEach("findAll", Map.of(), u -> sb.append(u.userName).append(','));
            assertEquals(3, rows);
            assertEquals("u0,u1,u2,", sb.toString());
        }

        @Test
        @DisplayName("回调抛异常会向外传播，不静默吞掉")
        void propagatesException() {
            soft.insert(new TestEntities.SoftUser("u0"));
            assertThrows(IllegalStateException.class, () ->
                    soft.forEach("findAll", Map.of(), u -> {
                        throw new IllegalStateException("boom");
                    }));
        }

        @Test
        @DisplayName("上千行也逐行消费，不需要一次装进 List")
        void largeSet() {
            List<TestEntities.SoftUser> list = new ArrayList<>();
            for (int i = 0; i < 1200; i++) {
                list.add(new TestEntities.SoftUser("u" + i));
            }
            soft.insertBatch(list);

            int[] seen = {0};
            int rows = soft.forEach("findAll", Map.of(), u -> seen[0]++);
            assertEquals(1200, rows);
            assertEquals(1200, seen[0]);
        }
    }

    @Nested
    @DisplayName("分页方言")
    class DialectRewrite {

        @Test
        @DisplayName("回收站与游标生成的 LIMIT 都会翻译成 FETCH FIRST")
        void limitIsTranslated() {
            var desc = io.github.jsonsql.meta.EntityDescriptor.of(TestEntities.SoftUser.class);
            Dialect oracle = Dialect.oracleLike("oracle");

            String recycle = oracle.rewritePaging(
                    io.github.jsonsql.sql.CrudSql.findDeleted(desc, 10).sql());
            assertTrue(recycle.contains("FETCH FIRST 10 ROWS ONLY"), recycle);

            String cursor = oracle.rewritePaging(
                    io.github.jsonsql.sql.CrudSql.findAfter(desc, 0L, 20).sql());
            assertTrue(cursor.contains("FETCH FIRST 20 ROWS ONLY"), cursor);
        }
    }
}
