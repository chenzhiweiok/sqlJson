package io.github.jsonsql;

import io.github.jsonsql.criteria.Criteria;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("条件能力：existsBy / updateBy")
class CriteriaOpsTest {

    private SqlJsonRepository<TestEntities.SoftUser> soft;
    private SqlJsonRepository<TestEntities.User> users;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = TestDb.fresh();
        soft = SqlJsonRepository.of(TestEntities.SoftUser.class, ds);
        users = SqlJsonRepository.of(TestEntities.User.class, ds);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("existsBy 命中即返回 true，与条件无关的数据不影响结果")
    void existsByHit() {
        long id = soft.insert(new TestEntities.SoftUser("张三"));
        soft.insert(new TestEntities.SoftUser("李四"));

        assertTrue(soft.existsBy("userName", "张三"));
        assertTrue(soft.existsBy(Criteria.of().eq("userName", "李四").isNotNull("createdAt")));
        assertFalse(soft.existsBy("userName", "王五"));
        assertTrue(soft.existsBy((Criteria) null), "空条件表示表里有没有数据");
        assertEquals(id, soft.findById(id).id);
    }

    @Test
    @DisplayName("existsBy 忽略已删数据")
    void existsByExcludesDeleted() {
        long id = soft.insert(new TestEntities.SoftUser("张三"));
        soft.deleteById(id);
        assertFalse(soft.existsBy("userName", "张三"));
    }

    @Test
    @DisplayName("updateBy 按条件批量改值，并刷新审计字段")
    void updateByCondition() {
        long a = soft.insert(new TestEntities.SoftUser("待停用"));
        long b = soft.insert(new TestEntities.SoftUser("待停用"));
        soft.insert(new TestEntities.SoftUser("保留"));

        int rows = soft.updateBy(
                Criteria.of().eq("userName", "待停用").lt("createdAt", LocalDateTime.now().plusMinutes(1)),
                Map.of("userName", "已停用"));

        assertEquals(2, rows);
        assertEquals(2, soft.countBy("userName", "已停用"));
        assertEquals(1, soft.countBy("userName", "保留"));
        assertEquals(a, soft.findById(a).id);
        assertEquals(b, soft.findById(b).id);
    }

    @Test
    @DisplayName("updateBy 拒绝空条件，避免误改全表")
    void updateByRequiresCondition() {
        soft.insert(new TestEntities.SoftUser("张三"));
        assertThrows(SqlSpecException.class, () -> soft.updateBy(null, Map.of("userName", "x")));
        assertThrows(SqlSpecException.class, () -> soft.updateBy(Criteria.of(), Map.of("userName", "x")));
        assertEquals(1, soft.countBy("userName", "张三"), "数据不应被改动");
    }

    @Test
    @DisplayName("updateBy 的 SET 列同样受白名单保护")
    void updateByRejectsProtectedColumns() {
        soft.insert(new TestEntities.SoftUser("张三"));
        assertThrows(SqlSpecException.class,
                () -> soft.updateBy(Criteria.of().eq("userName", "张三"), Map.of("id", 9L)));
        assertThrows(SqlSpecException.class,
                () -> soft.updateBy(Criteria.of().eq("userName", "张三"), Map.of("deleted", 1)));
    }

    @Test
    @DisplayName("updateBy 不会改到已删数据")
    void updateByExcludesDeleted() {
        long id = soft.insert(new TestEntities.SoftUser("张三"));
        soft.deleteById(id);
        assertEquals(0, soft.updateBy(Criteria.of().eq("userName", "张三"), Map.of("userName", "李四")));
        assertEquals(1, soft.findDeleted(10).size(), "已删记录仍在回收站");
    }

    @Test
    @DisplayName("无软删列的实体也能用条件更新")
    void updateByOnPlainEntity() {
        users.insert(new TestEntities.User("张三", 20, "0", "A"));
        users.insert(new TestEntities.User("李四", 21, "0", "A"));

        int rows = users.updateBy(Criteria.of().eq("status", "A"), Map.of("status", "B"));
        assertEquals(2, rows);
        assertEquals(0, users.countBy("status", "A"));
        assertEquals(2, users.countBy("status", "B"));
    }
}
