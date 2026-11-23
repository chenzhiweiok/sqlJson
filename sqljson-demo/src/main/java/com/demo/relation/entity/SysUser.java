package com.demo.relation.entity;

import io.github.jsonsql.SqlColumn;
import io.github.jsonsql.SqlJson;

import java.time.LocalDateTime;

/**
 * 业务实体：用户。
 *
 * <p>逻辑删除与审计字段都在注解里声明，框架负责：
 * 查询自动带 {@code deleted = 0}、删除改写为 {@code update ... set deleted = 1}、
 * 插入/更新自动填 created_at / updated_at / created_by / updated_by。
 */
@SqlJson(value = "user", table = "sys_user", resource = "sqls/sys_user.json")
public class SysUser {

    @SqlColumn(id = true, generated = true)
    private Long id;

    @SqlColumn("user_name")
    private String userName;

    private String email;
    private String dept;
    private String status;

    /** 演示用：SHA-256(salt + 密码)，不引入 Spring Security */
    @SqlColumn("password_hash")
    private String passwordHash;

    /** ADMIN / MEMBER */
    private String role;

    @SqlColumn(deleted = true)
    private Integer deleted;

    @SqlColumn("created_at")
    private LocalDateTime createdAt;

    @SqlColumn("updated_at")
    private LocalDateTime updatedAt;

    @SqlColumn("created_by")
    private String createdBy;

    @SqlColumn("updated_by")
    private String updatedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDept() {
        return dept;
    }

    public void setDept(String dept) {
        this.dept = dept;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
