package com.demo.relation.config;

import com.demo.relation.auth.AuthService;
import com.demo.relation.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 后台预热数据库连接。
 *
 * <p>MySQL 驱动首次连接会做一次反向 DNS，DNS 受限的机器上会阻塞几十秒。
 * 如果这发生在第一个 HTTP 请求里，页面会「打开就是白的」而没有任何报错。
 * 所以这里用独立线程提前建连，启动日志里直接给出耗时。
 */
@Component
public class DbWarmer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DbWarmer.class);

    private final DataSource dataSource;
    private final UserRepository users;

    public DbWarmer(DataSource dataSource, UserRepository users) {
        this.dataSource = dataSource;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            try (Connection c = dataSource.getConnection()) {
                LOG.info("数据库就绪，耗时 {}ms（{}）", System.currentTimeMillis() - start,
                        c.getMetaData().getURL());
                long total = users.all().size();
                LOG.info("现有用户 {} 条，登录密码默认 123456（演示数据）", total);
            } catch (Exception e) {
                LOG.error("数据库连接失败：{}", e.getMessage(), e);
            }
        }, "db-warmer");
        t.setDaemon(true);
        t.start();
    }

    /** 生成演示密码哈希，方便首次初始化数据时对齐 */
    public static void main(String[] args) {
        System.out.println(AuthService.hash("123456"));
    }
}
