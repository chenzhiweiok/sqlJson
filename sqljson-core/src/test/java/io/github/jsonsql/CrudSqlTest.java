package io.github.jsonsql;

import io.github.jsonsql.jdbc.CrudSql;
import io.github.jsonsql.jdbc.SqlStatement;
import io.github.jsonsql.meta.EntityDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SQL 生成层可以脱离数据库单独测。
 *
 * <p>这是这次分层最实际的收益：以前这些 SQL 藏在仓库私有方法里，
 * 想验证「逻辑删除的 WHERE 到底带了什么」必须起一个 H2；现在只要元数据。
 */
@DisplayName("CrudSql：不连库也能验证生成的 SQL")
class CrudSqlTest {

    private static EntityDescriptor soft() {
        return EntityDescriptor.of(TestEntities.SoftUser.class);
    }

    @Test
    @DisplayName("逻辑删除：置 1、刷新审计、带 deleted = 0 保证幂等")
    void softDelete() {
        SqlStatement s = CrudSql.softDelete(soft(), 7L);

        assertTrue(s.sql().startsWith("update t_du set deleted = 1"), s.sql());
        assertTrue(s.sql().contains("updated_at = ?"), "应刷新更新时间: " + s.sql());
        assertTrue(s.sql().endsWith("where id = ? and deleted = 0"), "重复删除要影响 0 行: " + s.sql());
        // 参数顺序必须与占位符一致：时间 → 操作人 → 主键
        assertEquals(3, s.args().size(), s.args().toString());
        assertEquals(7L, s.args().get(2));
    }

    @Test
    @DisplayName("恢复：置 0，且不再追加 deleted = 0（否则永远恢复不了）")
    void restore() {
        SqlStatement s = CrudSql.restore(soft(), 7L);

        assertTrue(s.sql().contains("set deleted = 0"), s.sql());
        assertTrue(s.sql().endsWith("where id = ?"), s.sql());
        assertTrue(!s.sql().endsWith("and deleted = 0"), "恢复的 WHERE 不能带未删除条件");
        assertEquals(7L, s.args().get(s.args().size() - 1));
    }

    @Test
    @DisplayName("没有逻辑删除列时直接拒绝，而不是生成一条错误的 SQL")
    void requireSoftDeleteColumn() {
        EntityDescriptor plain = EntityDescriptor.of(TestEntities.User.class);
        SqlSpecException e = assertThrows(SqlSpecException.class, () -> CrudSql.softDelete(plain, 1L));
        assertTrue(e.getMessage().contains("没有逻辑删除列"), e.getMessage());
    }

    @Test
    @DisplayName("批量插入：只写有值的列，按 chunk 切开")
    void insertBatchChunks() {
        TestEntities.SoftUser a = new TestEntities.SoftUser("张三");
        TestEntities.SoftUser b = new TestEntities.SoftUser("李四");

        List<SqlStatement> one = CrudSql.insertBatch(soft(), List.of(a, b), 2);
        assertEquals(1, one.size());
        assertTrue(one.get(0).sql().startsWith("insert into t_du (user_name) values"), one.get(0).sql());
        assertEquals(2, one.get(0).args().size());

        List<SqlStatement> two = CrudSql.insertBatch(soft(), List.of(a, b), 1);
        assertEquals(2, two.size(), "chunkSize=1 时应拆成两条");
    }

    @Test
    @DisplayName("批量更新：CASE WHEN 拼装，并过滤已逻辑删除的数据")
    void updateBatch() {
        TestEntities.SoftUser a = new TestEntities.SoftUser("张三");
        a.id = 1L;
        TestEntities.SoftUser b = new TestEntities.SoftUser("李四");
        b.id = 2L;

        SqlStatement s = CrudSql.updateBatch(soft(), List.of(a, b));
        assertTrue(s.sql().contains("user_name = case id"), s.sql());
        assertTrue(s.sql().contains("when ? then ?"), s.sql());
        assertTrue(s.sql().endsWith("and deleted = 0"), "不能把已删数据改活: " + s.sql());
        // 2 个实体 × (id, 值) + IN 的 2 个 id
        assertEquals(6, s.args().size(), s.args().toString());
    }

    @Test
    @DisplayName("没有任何可更新值时返回 null，由调用方按 0 行处理")
    void updateBatchWithoutValues() {
        TestEntities.SoftUser a = new TestEntities.SoftUser(null);
        a.id = 1L;
        assertNull(CrudSql.updateBatch(soft(), List.of(a)));
    }

    @Test
    @DisplayName("回收站查询：显式 deleted = 1，条数取正数")
    void findDeleted() {
        SqlStatement s = CrudSql.findDeleted(soft(), 0);
        assertTrue(s.sql().contains("deleted = 1"), s.sql());
        assertTrue(s.sql().endsWith("limit 1"), "limit 至少为 1: " + s.sql());
        assertTrue(s.args().isEmpty());
    }
}
