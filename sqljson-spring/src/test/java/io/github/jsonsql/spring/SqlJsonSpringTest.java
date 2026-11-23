package io.github.jsonsql.spring;

import io.github.jsonsql.PageResult;
import io.github.jsonsql.SqlJsonRepository;
import io.github.jsonsql.spring.fixture.TestUser;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Spring 装配：@EnableSqlJson 扫描实体并注册仓库 Bean")
class SqlJsonSpringTest {

    private static AnnotationConfigApplicationContext context;

    @Configuration
    @EnableSqlJson(basePackages = "io.github.jsonsql.spring.fixture")
    static class Config {
        @Bean
        DataSource dataSource() throws Exception {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:spring_test;DB_CLOSE_DELAY=-1");
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("drop table if exists t_spring_user");
                st.execute("""
                        create table t_spring_user (
                          id bigint primary key auto_increment,
                          user_name varchar(64),
                          age int
                        )
                        """);
            }
            return ds;
        }
    }

    @BeforeAll
    static void boot() {
        context = new AnnotationConfigApplicationContext(Config.class);
    }

    @AfterAll
    static void shutdown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("Bean 按「命名空间 + Repository」注册")
    void beanRegistered() {
        assertTrue(context.containsBean("tuSqlJsonRepository"), "应注册 tuSqlJsonRepository");
        assertNotNull(context.getBean("tuSqlJsonRepository", SqlJsonRepository.class));
    }

    @Test
    @DisplayName("注入后可直接用默认 CRUD 与分页")
    @SuppressWarnings("unchecked")
    void crudWorks() {
        SqlJsonRepository<TestUser> repo =
                (SqlJsonRepository<TestUser>) context.getBean("tuSqlJsonRepository", SqlJsonRepository.class);

        long id = repo.insert(new TestUser("邱老师", 32));
        assertTrue(id > 0, "主键应回填");

        repo.insert(new TestUser("张三", 28));
        repo.insert(new TestUser("小明", 15));

        List<TestUser> all = repo.findAll();
        assertEquals(3, all.size());

        PageResult<TestUser> page = repo.findPage(1, 2);
        assertEquals(3, page.total());
        assertEquals(2, page.list().size());

        repo.updateById(withBalance(id));
        assertEquals("改名后", repo.findById(id).userName);

        assertEquals(1, repo.deleteById(id));
        assertEquals(2, repo.findAll().size());
    }

    private TestUser withBalance(long id) {
        TestUser u = new TestUser("改名后", 33);
        u.id = id;
        return u;
    }
}
