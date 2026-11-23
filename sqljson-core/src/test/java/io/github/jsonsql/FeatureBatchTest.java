package io.github.jsonsql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("第 1 批能力：逻辑删除 / 审计 / 批量 / 方言")
class FeatureBatchTest {

    private JdbcDataSource ds;
    private SqlJsonRepository<TestEntities.SoftUser> repo;

    @BeforeEach
    void setUp() {
        ds = TestDb.fresh();
        repo = SqlJsonRepository.of(TestEntities.SoftUser.class, ds);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Nested
    @DisplayName("逻辑删除")
    class SoftDelete {

        @Test
        @DisplayName("deleteById 改写为 update，数据还在但查不到")
        void deleteBecomesUpdate() {
            long id = repo.insert(new TestEntities.SoftUser("张三"));
            assertEquals(1, repo.deleteById(id));

            assertNull(repo.findById(id), "已删数据不应被 findById 查到");
            assertEquals(0, repo.findAll().size());
            assertEquals(1, repo.findDeleted(10).size(), "回收站里应该能看到");
        }

        @Test
        @DisplayName("查询语句自动带 deleted = 0")
        void addsFilter() {
            String sql = repo.explain("findAll", Map.of());
            assertTrue(sql.contains("deleted = 0"), "实际: " + sql);
        }

        @Test
        @DisplayName("分页 count 也带过滤，总数不含已删")
        void countExcludesDeleted() {
            long a = repo.insert(new TestEntities.SoftUser("A"));
            repo.insert(new TestEntities.SoftUser("B"));
            repo.deleteById(a);

            PageResult<TestEntities.SoftUser> page = repo.findPage(1, 10);
            assertEquals(1, page.total(), "count 必须排除已删数据");
            assertEquals(1, page.list().size());
        }

        @Test
        @DisplayName("restoreById 恢复后被删数据重新可见")
        void restores() {
            long id = repo.insert(new TestEntities.SoftUser("要恢复的"));
            repo.deleteById(id);
            assertNull(repo.findById(id));

            assertEquals(1, repo.restoreById(id));
            assertNotNull(repo.findById(id));
        }

        @Test
        @DisplayName("重复删除返回 0 行（已删的不能再删）")
        void deleteTwice() {
            long id = repo.insert(new TestEntities.SoftUser("重复删"));
            assertEquals(1, repo.deleteById(id));
            assertEquals(0, repo.deleteById(id));
        }

        @Test
        @DisplayName("已删除的行不能被更新")
        void cannotUpdateDeleted() {
            long id = repo.insert(new TestEntities.SoftUser("旧名字"));
            repo.deleteById(id);

            TestEntities.SoftUser patch = new TestEntities.SoftUser();
            patch.id = id;
            patch.userName = "新名字";
            assertEquals(0, repo.updateById(patch), "更新语句应带上 deleted = 0 过滤");
        }
    }

    @Nested
    @DisplayName("审计字段")
    class Audit {

        @Test
        @DisplayName("insert 自动填 createdAt / createdBy")
        void fillsOnInsert() {
            CurrentUser.set("qiu");
            TestEntities.SoftUser u = new TestEntities.SoftUser("审计");
            repo.insert(u);

            assertNotNull(u.createdAt);
            assertEquals("qiu", u.createdBy);
            assertEquals("qiu", u.updatedBy);
        }

        @Test
        @DisplayName("updateById 刷新 updatedAt，不动 createdAt")
        void refreshesOnUpdate() throws Exception {
            CurrentUser.set("qiu");
            TestEntities.SoftUser u = new TestEntities.SoftUser("审计2");
            repo.insert(u);
            LocalDateTime created = u.createdAt;

            Thread.sleep(5);
            CurrentUser.set("wang");
            TestEntities.SoftUser patch = new TestEntities.SoftUser();
            patch.id = u.id;
            patch.userName = "改了名";
            repo.updateById(patch);

            TestEntities.SoftUser after = repo.findById(u.id);
            assertEquals("wang", after.updatedBy);
            assertEquals(created.toLocalDate(), after.createdAt.toLocalDate());
            assertFalse(after.updatedAt.isBefore(created), "updatedAt 应被刷新");
        }

        @Test
        @DisplayName("调用方显式传的值不被覆盖")
        void keepsExplicitValue() {
            CurrentUser.set("someone");
            TestEntities.SoftUser u = new TestEntities.SoftUser("显式");
            u.createdBy = "手写的人";
            repo.insert(u);
            assertEquals("手写的人", u.createdBy);
        }
    }

    @Nested
    @DisplayName("批量")
    class Batch {

        @Test
        @DisplayName("insertBatch 一次写多行")
        void insertBatch() {
            int n = repo.insertBatch(List.of(
                    new TestEntities.SoftUser("批量1"),
                    new TestEntities.SoftUser("批量2"),
                    new TestEntities.SoftUser("批量3")));
            assertEquals(3, n);
            assertEquals(3, repo.findAll().size());
        }

        @Test
        @DisplayName("insertBatch 会填审计字段")
        void batchFillsAudit() {
            CurrentUser.set("batch");
            TestEntities.SoftUser a = new TestEntities.SoftUser("x");
            TestEntities.SoftUser b = new TestEntities.SoftUser("y");
            repo.insertBatch(List.of(a, b));
            assertEquals("batch", a.createdBy);
            assertEquals("batch", b.createdBy);
        }

        @Test
        @DisplayName("updateBatch 用 CASE WHEN，只改传了的列")
        void updateBatch() {
            long id1 = repo.insert(new TestEntities.SoftUser("原名1"));
            long id2 = repo.insert(new TestEntities.SoftUser("原名2"));

            TestEntities.SoftUser p1 = new TestEntities.SoftUser();
            p1.id = id1;
            p1.userName = "新名1";
            TestEntities.SoftUser p2 = new TestEntities.SoftUser();
            p2.id = id2;
            p2.userName = "新名2";

            assertEquals(2, repo.updateBatch(List.of(p1, p2)));
            assertEquals("新名1", repo.findById(id1).userName);
            assertEquals("新名2", repo.findById(id2).userName);
        }

        @Test
        @DisplayName("updateBatch 不碰已逻辑删除的行")
        void updateBatchSkipsDeleted() {
            long id = repo.insert(new TestEntities.SoftUser("会被删"));
            repo.deleteById(id);

            TestEntities.SoftUser patch = new TestEntities.SoftUser();
            patch.id = id;
            patch.userName = "不该生效";
            assertEquals(0, repo.updateBatch(List.of(patch)));
        }

        @Test
        @DisplayName("空集合直接返回 0")
        void emptyBatch() {
            assertEquals(0, repo.insertBatch(List.of()));
            assertEquals(0, repo.updateBatch(List.of()));
        }
    }

    @Nested
    @DisplayName("方言")
    class Dialects {

        @Test
        @DisplayName("MySQL / PG 保持 LIMIT OFFSET")
        void mysqlKeeps() {
            String sql = "select id from t limit 10 offset 20";
            assertEquals(sql, Dialect.mysql().rewritePaging(sql));
            assertEquals(sql, Dialect.postgres().rewritePaging(sql));
        }

        @Test
        @DisplayName("Oracle / 达梦 改写成 OFFSET ... FETCH")
        void oracleRewrites() {
            String sql = "select id from t limit 10 offset 20";
            assertEquals("select id from t OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
                    Dialect.oracleLike("oracle").rewritePaging(sql));
            assertEquals("select id from t OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
                    Dialect.of("dm").rewritePaging(sql));
        }

        @Test
        @DisplayName("只有 LIMIT 时改写成 FETCH FIRST")
        void oracleNoOffset() {
            assertEquals("select id from t FETCH FIRST 5 ROWS ONLY",
                    Dialect.oracleLike("oracle").rewritePaging("select id from t limit 5"));
        }

        @Test
        @DisplayName("仓库层按方言输出分页 SQL")
        void repositoryUsesDialect() {
            SqlJsonRepository<TestEntities.SoftUser> oracleRepo =
                    SqlJsonRepository.of(TestEntities.SoftUser.class, ds, Dialect.oracleLike("oracle"));
            String sql = oracleRepo.explainPage("findPage", Map.of("pageNo", 2, "pageSize", 5));
            assertTrue(sql.contains("FETCH NEXT"), "实际: " + sql);
        }
    }

    @Test
    @DisplayName("没有逻辑删除列的实体，deleteById 仍是物理删除")
    void physicalDeleteUnchanged() {
        SqlJsonRepository<TestEntities.User> users = SqlJsonRepository.of(TestEntities.User.class, ds);
        users.insert(new TestEntities.User("物理删", 20, "1.00", "A"));
        long id = users.findAll().get(0).id;
        users.deleteById(id);
        assertEquals(0, users.findAll().size());
        assertThrows(SqlSpecException.class, () -> users.restoreById(id));
    }
}
