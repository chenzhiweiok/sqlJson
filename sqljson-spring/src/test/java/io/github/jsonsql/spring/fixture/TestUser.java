package io.github.jsonsql.spring.fixture;

import io.github.jsonsql.SqlColumn;
import io.github.jsonsql.SqlJson;

/** 测试用实体：验证扫描与默认 CRUD 装配 */
@SqlJson(value = "tu", table = "t_spring_user")
public class TestUser {

    @SqlColumn(id = true, generated = true)
    public Long id;

    @SqlColumn("user_name")
    public String userName;

    public Integer age;

    public TestUser() {
    }

    public TestUser(String userName, Integer age) {
        this.userName = userName;
        this.age = age;
    }
}
