package io.github.jsonsql;

import java.math.BigDecimal;

/** 测试用实体：一个挂复杂 SQL 资源，一个只有默认 CRUD。 */
final class TestEntities {

    private TestEntities() {
    }

    @SqlJson(value = "user", table = "t_user", resource = "sqls/user-complex.json")
    static class User {
        @SqlColumn(id = true, generated = true)
        Long id;
        @SqlColumn("user_name")
        String userName;
        Integer age;
        BigDecimal balance;
        String status;

        User() {
        }

        User(String userName, int age, String balance, String status) {
            this.userName = userName;
            this.age = age;
            this.balance = new BigDecimal(balance);
            this.status = status;
        }

        @Override
        public String toString() {
            return "User{" + id + "," + userName + "," + age + "," + balance + "," + status + "}";
        }
    }

    @SqlJson(value = "du", table = "t_du")
    static class SoftUser {
        @SqlColumn(id = true, generated = true)
        Long id;
        @SqlColumn("user_name")
        String userName;
        @SqlColumn(deleted = true)
        Integer deleted;
        @SqlColumn(createdAt = true)
        java.time.LocalDateTime createdAt;
        @SqlColumn(updatedAt = true)
        java.time.LocalDateTime updatedAt;
        @SqlColumn(createdBy = true)
        String createdBy;
        @SqlColumn(updatedBy = true)
        String updatedBy;

        SoftUser() {
        }

        SoftUser(String userName) {
            this.userName = userName;
        }
    }

    @SqlJson(value = "orders", table = "t_orders")
    static class Order {
        @SqlColumn(id = true, generated = true)
        Long id;
        String orderNo;
        BigDecimal amount;

        Order() {
        }

        Order(String orderNo, String amount) {
            this.orderNo = orderNo;
            this.amount = new BigDecimal(amount);
        }
    }
}
