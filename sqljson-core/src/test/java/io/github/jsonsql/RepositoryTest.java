package io.github.jsonsql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("注解式仓库 SqlJsonRepository")
class RepositoryTest {

    private JdbcDataSource ds;
    private SqlJsonRepository<TestEntities.User> repo;

    @BeforeEach
    void setUp() {
        ds = TestDb.fresh();
        TestDb.seedUsers(ds);
        repo = SqlJsonRepository.of(TestEntities.User.class, ds);
    }

    @Test
    @DisplayName("insert 回填自增主键")
    void insertBackfillsId() {
        TestEntities.User u = new TestEntities.User("新用户", 20, "1.00", "A");
        long id = repo.insert(u);
        assertTrue(id > 0);
        assertEquals(id, u.id);
    }

    @Test
    @DisplayName("findById 能读到全部字段（列名下划线 <-> 字段名驼峰）")
    void findsById() {
        TestEntities.User u = repo.findById(1L);
        assertNotNull(u);
        assertEquals("邱老师", u.userName);
        assertEquals(32, u.age);
        assertEquals(0, new BigDecimal("1200.50").compareTo(u.balance));
    }

    @Test
    @DisplayName("findById 查不到返回 null")
    void findsNothing() {
        assertNull(repo.findById(999L));
    }

    @Test
    @DisplayName("findAll 返回全部")
    void findsAll() {
        assertEquals(4, repo.findAll().size());
    }

    @Test
    @DisplayName("updateById 只改传了的字段，其他字段不被 null 覆盖")
    void updatesOnlyProvidedFields() {
        TestEntities.User patch = new TestEntities.User();
        patch.id = 1L;
        patch.balance = new BigDecimal("8888.88");

        assertEquals(1, repo.updateById(patch));

        TestEntities.User after = repo.findById(1L);
        assertEquals("邱老师", after.userName);
        assertEquals(32, after.age);
        assertEquals(0, new BigDecimal("8888.88").compareTo(after.balance));
    }

    @Test
    @DisplayName("deleteById 删除一条")
    void deletesById() {
        assertEquals(1, repo.deleteById(3L));
        assertEquals(3, repo.findAll().size());
        assertNull(repo.findById(3L));
    }

    @Test
    @DisplayName("findPage 返回总数与页数")
    void findsPage() {
        PageResult<TestEntities.User> page = repo.findPage(1, 2);
        assertEquals(2, page.list().size());
        assertEquals(4, page.total());
        assertEquals(2, page.pages());
        assertTrue(page.hasNext());

        PageResult<TestEntities.User> last = repo.findPage(2, 2);
        assertEquals(2, last.list().size());
        assertFalse(last.hasNext());
    }

    @Test
    @DisplayName("资源里的同名声明的覆盖默认 findPage")
    void resourceOverridesDefault() {
        String sql = repo.explain("findPage", Map.of("pageNo", 1, "pageSize", 5));
        assertTrue(sql.contains("(? IS NULL OR status = ?)"), "实际: " + sql);
    }

    @Test
    @DisplayName("复杂查询：关键字模糊匹配 + 分页")
    void searchesByKeyword() {
        PageResult<TestEntities.User> page = repo.page("search", 1, 10, Map.of("keyword", "%张%"));
        assertEquals(1, page.total());
        assertEquals("张三", page.list().get(0).userName);
    }

    @Test
    @DisplayName("聚合查询走 pageMaps，countSql 显式指定")
    void aggregates() {
        PageResult<Map<String, Object>> page = repo.pageMaps("countByStatus", 1, 10, Map.of());
        assertEquals(2, page.total());
        assertEquals(2, page.list().size());
        assertTrue(page.list().get(0).containsKey("status"));
        assertTrue(page.list().get(0).containsKey("cnt"));
    }

    @Test
    @DisplayName("statements 列出默认 + 资源里的全部语句")
    void listsStatements() {
        var names = repo.statements();
        List<String> expected = List.of("insert", "updateById", "deleteById",
                "findById", "findAll", "findPage", "search", "countByStatus");
        assertTrue(names.containsAll(expected), "实际: " + names);
    }

    @Test
    @DisplayName("execute 执行 JSON 之外的临时 SQL")
    void executesRawSql() {
        assertEquals(1, repo.execute("update t_user set status = ? where id = ?", List.of("C", 3L)));
        assertEquals("C", repo.findById(3L).status);
    }
}
