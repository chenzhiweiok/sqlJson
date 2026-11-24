package io.github.jsonsql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("upsertBatch：原生合并与降级路径")
class UpsertTest {

    private JdbcDataSource ds;

    @BeforeEach
    void setUp() {
        ds = TestDb.fresh();
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("H2 原生 MERGE：有则更新、无则插入")
    void nativeMerge() {
        SqlJsonRepository<TestEntities.DictItem> repo =
                SqlJsonRepository.of(TestEntities.DictItem.class, ds, Dialect.h2());

        assertEquals(2, repo.upsertBatch(List.of(
                new TestEntities.DictItem(1, "a", "旧名"),
                new TestEntities.DictItem(2, "b", "乙"))));

        assertEquals(2, repo.upsertBatch(List.of(
                new TestEntities.DictItem(1, "a", "新名"),
                new TestEntities.DictItem(3, "c", "丙"))));

        assertEquals(3, repo.countAll(), "不应产生重复行");
        assertEquals("新名", repo.findById(1L).name);
        assertEquals("丙", repo.findById(3L).name);
        assertEquals("乙", repo.findById(2L).name, "未参与的行不应被改动");
    }

    @Test
    @DisplayName("方言不支持时降级为「查存在 + 分流更新/插入」")
    void fallbackPath() {
        SqlJsonRepository<TestEntities.DictItem> repo =
                SqlJsonRepository.of(TestEntities.DictItem.class, ds, Dialect.oracleLike("oracle"));

        assertEquals(2, repo.upsertBatch(List.of(
                new TestEntities.DictItem(1, "a", "旧名"),
                new TestEntities.DictItem(2, "b", "乙"))));

        assertEquals(2, repo.upsertBatch(List.of(
                new TestEntities.DictItem(1, "a", "新名"),
                new TestEntities.DictItem(4, "d", "丁"))));

        assertEquals(3, repo.countAll());
        assertEquals("新名", repo.findById(1L).name);
        assertEquals("丁", repo.findById(4L).name);
    }

    @Test
    @DisplayName("自增主键的实体拒绝 upsert，并说明原因")
    void rejectsGeneratedId() {
        SqlJsonRepository<TestEntities.User> repo = SqlJsonRepository.of(TestEntities.User.class, ds);
        SqlSpecException e = assertThrows(SqlSpecException.class,
                () -> repo.upsertBatch(List.of(new TestEntities.User("张三", 20, "0", "A"))));
        assertTrue(e.getMessage().contains("主键"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("MySQL 生成 on duplicate key update")
    void mysqlSql() {
        String sql = Dialect.mysql().upsertStatement(
                "insert into t_dict (id, code, name) values (?, ?, ?)",
                "t_dict", List.of("id", "code", "name"), List.of("id"), List.of("code", "name"), 1);
        assertTrue(sql.contains("on duplicate key update"), sql);
        assertTrue(sql.contains("code = values(code)"), sql);
        assertTrue(sql.contains("name = values(name)"), sql);
    }

    @Test
    @DisplayName("PostgreSQL 生成 on conflict ... do update set ... excluded")
    void postgresSql() {
        String sql = Dialect.postgres().upsertStatement(
                "insert into t_dict (id, code) values (?, ?)",
                "t_dict", List.of("id", "code"), List.of("id"), List.of("code"), 1);
        assertTrue(sql.contains("on conflict (id) do update set"), sql);
        assertTrue(sql.contains("code = excluded.code"), sql);
    }
}
