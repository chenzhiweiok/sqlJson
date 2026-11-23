package com.demo.relation.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 登录后放进 HttpSession 的最小身份，只保留页面要用的字段 */
public record SessionUser(long id, String userName, String role) {

    @JsonIgnore
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
