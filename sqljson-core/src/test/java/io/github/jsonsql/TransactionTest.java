package io.github.jsonsql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.jsonsql.support.TxContext;

@DisplayName("事务 TxContext")
class TransactionTest {

    private JdbcDataSource ds;
    private SqlJsonRepository<TestEntities.User> users;
    private SqlJsonRepository<TestEntities.Order> orders;

    @BeforeEach
    void setUp() {
        ds = TestDb.fresh();
        users = SqlJsonRepository.of(TestEntities.User.class, ds);
        orders = SqlJsonRepository.of(TestEntities.Order.class, ds);
    }

    @AfterEach
    void tearDown() {
        TxContext.reset();
    }

    @Test
    @DisplayName("正常结束提交")
    void commits() {
        users.tx(() -> {
            assertTrue(users.inTransaction());
            users.insert(new TestEntities.User("A", 20, "1.00", "A"));
            users.insert(new TestEntities.User("B", 21, "2.00", "A"));
        });
        assertFalse(users.inTransaction());
        assertEquals(2, users.findAll().size());
    }

    @Test
    @DisplayName("抛异常整段回滚")
    void rollsBack() {
        assertThrows(IllegalStateException.class, () -> users.tx(() -> {
            users.insert(new TestEntities.User("会被回滚", 20, "1.00", "A"));
            throw new IllegalStateException("业务失败");
        }));
        assertEquals(0, users.findAll().size());
    }

    @Test
    @DisplayName("嵌套事务：内层失败只回滚自己，外层继续")
    void nestedSavepoint() {
        users.tx(() -> {
            users.insert(new TestEntities.User("外层-A", 30, "1.00", "A"));
            assertThrows(IllegalStateException.class, () -> users.tx(() -> {
                users.insert(new TestEntities.User("内层-B", 31, "1.00", "A"));
                throw new IllegalStateException("内层失败");
            }));
            users.insert(new TestEntities.User("外层-C", 33, "1.00", "A"));
        });

        List<String> names = users.findAll().stream().map(u -> u.userName).toList();
        assertTrue(names.contains("外层-A"), "外层第一条应保留");
        assertTrue(names.contains("外层-C"), "内层失败后外层应能继续");
        assertFalse(names.contains("内层-B"), "内层的写入应被回滚");
        assertEquals(2, names.size());
    }

    @Test
    @DisplayName("跨实体共享同一连接，一起回滚")
    void sharesConnectionAcrossEntities() {
        assertThrows(IllegalStateException.class, () -> users.tx(() -> {
            users.insert(new TestEntities.User("跨实体", 40, "1.00", "A"));
            orders.insert(new TestEntities.Order("NO-1", "9.90"));
            throw new IllegalStateException("故意失败");
        }));
        assertEquals(0, users.findAll().size(), "用户表应回滚");
        assertEquals(0, orders.findAll().size(), "订单表也应回滚");
    }

    @Test
    @DisplayName("带返回值的事务")
    void returnsValue() {
        long id = users.txGet(() -> users.insert(new TestEntities.User("有返回值", 25, "1.00", "A")));
        assertTrue(id > 0);
        assertEquals("有返回值", users.findById(id).userName);
        assertFalse(users.inTransaction(), "事务结束后必须清理线程上下文");
    }

    @Test
    @DisplayName("不在事务里时每次操作独立提交")
    void autoCommitOutsideTx() {
        assertFalse(users.inTransaction());
        users.insert(new TestEntities.User("独立", 20, "1.00", "A"));
        assertEquals(1, users.findAll().size());
    }

    @Test
    @DisplayName("execute 也在事务里，同样回滚")
    void rawSqlParticipatesInTx() {
        assertThrows(IllegalStateException.class, () -> users.tx(() -> {
            users.insert(new TestEntities.User("待改", 20, "1.00", "A"));
            users.execute("update t_user set status = ?", List.of("B"));
            throw new IllegalStateException("回滚");
        }));
        assertEquals(0, users.findAll().size());
    }
}
