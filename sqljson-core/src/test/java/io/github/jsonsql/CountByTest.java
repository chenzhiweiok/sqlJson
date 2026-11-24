package io.github.jsonsql;

import io.github.jsonsql.criteria.Criteria;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("countBy：条件计数")
class CountByTest {

    private JdbcDataSource ds;
    private SqlJsonRepository<TestEntities.User> users;
    private SqlJsonRepository<TestEntities.SoftUser> soft;

    @BeforeEach
    void setUp() {
        ds = TestDb.fresh();
        TestDb.seedUsers(ds);
        users = SqlJsonRepository.of(TestEntities.User.class, ds);
        soft = SqlJsonRepository.of(TestEntities.SoftUser.class, ds);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Nested
    @DisplayName("基础条件")
    class Basics {

        @Test
        @DisplayName("无条件等于全表行数")
        void countsAll() {
            assertEquals(4, users.countAll());
            assertEquals(4, users.countBy(Criteria.of()), "空条件应与 countAll 等价");
            assertEquals(4, users.countBy((Criteria) null), "null 应等同于空条件，不能 NPE");
        }

        @Test
        @DisplayName("等值条件")
        void eqCondition() {
            assertEquals(3, users.countBy("status", "A"));
        }

        @Test
        @DisplayName("接受列名，与字段名等价")
        void acceptsColumnName() {
            assertEquals(1, users.countBy("user_name", "张三"));
            assertEquals(1, users.countBy("userName", "张三"));
        }

        @Test
        @DisplayName("数值范围比较")
        void rangeConditions() {
            assertEquals(2, users.countBy(Criteria.of().gt("age", 30)), "32 与 35");
            assertEquals(3, users.countBy(Criteria.of().ge("age", 28)));
            assertEquals(1, users.countBy(Criteria.of().lt("age", 20)), "只有 17 岁");
            assertEquals(2, users.countBy(Criteria.of().le("age", 28)));
        }

        @Test
        @DisplayName("多个条件按 AND 组合")
        void andCombination() {
            assertEquals(2, users.countBy(Criteria.of().eq("status", "A").gt("age", 30)));
        }

        @Test
        @DisplayName("IN 列表")
        void inCondition() {
            assertEquals(2, users.countBy(Criteria.of().in("userName", List.of("张三", "李四"))));
        }

        @Test
        @DisplayName("空 IN 匹配不到任何行，而不是退化成全表")
        void emptyInMatchesNothing() {
            assertEquals(0, users.countBy(Criteria.of().in("userName", List.of())));
        }

        @Test
        @DisplayName("eq(null) 语义是 is null")
        void eqNullMeansIsNull() {
            TestEntities.User noStatus = new TestEntities.User();
            noStatus.userName = "无状态";
            users.insert(noStatus);

            assertEquals(1, users.countBy(Criteria.of().eq("status", null)));
            assertEquals(4, users.countBy(Criteria.of().isNotNull("status")));
        }
    }

    @Nested
    @DisplayName("模糊匹配")
    class LikeMatch {

        @Test
        @DisplayName("通配符按字面匹配，不会放大成全表")
        void wildcardsAreEscaped() {
            TestEntities.User u = new TestEntities.User();
            u.userName = "50%折扣";
            users.insert(u);

            assertEquals(1, users.countBy(Criteria.of().like("userName", Like.contains("50%"))),
                    "应只匹配字面含 50% 的那一条");
            assertEquals(1, users.countBy(Criteria.of().like("userName", "%")),
                    "用户输入的 % 不能变成通配符，否则会匹配全表");
        }

        @Test
        @DisplayName("前缀匹配可直接用字符串重载")
        void stringOverloadUsesContains() {
            assertEquals(1, users.countBy(Criteria.of().like("userName", "张")));
        }
    }

    @Nested
    @DisplayName("逻辑删除")
    class SoftDelete {

        @Test
        @DisplayName("统计自动排除已删数据")
        void excludesDeleted() {
            long a = soft.insert(new TestEntities.SoftUser("甲"));
            soft.insert(new TestEntities.SoftUser("乙"));
            assertEquals(2, soft.countAll());

            soft.deleteById(a);
            assertEquals(1, soft.countAll(), "已删数据不应被计入");
            assertEquals(1, soft.countBy("userName", "乙"));
            assertEquals(0, soft.countBy("userName", "甲"));
        }
    }

    @Nested
    @DisplayName("错误处理")
    class Errors {

        @Test
        @DisplayName("未知字段立刻报错，并列出可用字段")
        void unknownFieldFails() {
            SqlSpecException e = assertThrows(SqlSpecException.class,
                    () -> users.countBy("nickname", "张三"));
            assertTrue(e.getMessage().contains("nickname"), "实际: " + e.getMessage());
            assertTrue(e.getMessage().contains("userName"), "应提示可用字段，实际: " + e.getMessage());
        }

        @Test
        @DisplayName("比较类操作符不接受 null")
        void comparableRejectsNull() {
            assertThrows(SqlSpecException.class, () -> Criteria.of().gt("age", null));
        }

        @Test
        @DisplayName("IN 不接受 null 集合")
        void inRejectsNullCollection() {
            assertThrows(SqlSpecException.class, () -> Criteria.of().in("age", null));
        }
    }
}
