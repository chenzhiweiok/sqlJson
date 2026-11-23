package com.demo.relation.entity;

import io.github.jsonsql.SqlColumn;
import io.github.jsonsql.SqlJson;

import java.time.LocalDateTime;

/** 业务实体：关系边。relType 取值 FOLLOW / FRIEND / BLOCK。 */
@SqlJson(value = "relation", table = "sys_relation", resource = "sqls/sys_relation.json")
public class SysRelation {

    @SqlColumn(id = true, generated = true)
    private Long id;

    @SqlColumn("user_id")
    private Long userId;

    @SqlColumn("target_id")
    private Long targetId;

    @SqlColumn("rel_type")
    private String relType;

    @SqlColumn("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getRelType() {
        return relType;
    }

    public void setRelType(String relType) {
        this.relType = relType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
