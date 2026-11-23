package com.demo.relation.repository;

import com.demo.relation.entity.SysRelation;
import io.github.jsonsql.PageResult;
import io.github.jsonsql.SqlJsonRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 关系仓储：同上，只做参数整理。 */
@Repository
public class RelationRepository {

    private final SqlJsonRepository<SysRelation> repo;

    public RelationRepository(@Qualifier("relationSqlJsonRepository") SqlJsonRepository<SysRelation> repo) {
        this.repo = repo;
    }

    public PageResult<SysRelation> page(Long userId, String relType, int pageNo, int size) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("userId", userId);
        args.put("relType", relType == null || relType.isBlank() ? null : relType);
        return repo.page("findPage", pageNo, size, args);
    }

    public List<Map<String, Object>> ofUser(long userId) {
        return repo.queryMaps("ofUser", Map.of("userId", userId));
    }

    public List<Map<String, Object>> graph() {
        return repo.queryMaps("graph", Map.of());
    }

    public List<Map<String, Object>> statsByType() {
        return repo.queryMaps("statsByType", Map.of());
    }

    public List<Map<String, Object>> topUsers(int limit) {
        return repo.queryMaps("topUsers", Map.of("n", limit));
    }

    public boolean exists(long userId, long targetId, String relType) {
        List<Map<String, Object>> rows = repo.queryMaps("exists",
                Map.of("userId", userId, "targetId", targetId, "relType", relType));
        if (rows.isEmpty()) {
            return false;
        }
        Object cnt = rows.get(0).get("cnt");
        return cnt instanceof Number n && n.longValue() > 0;
    }

    /** 与该用户相关的关系数（入边 + 出边），删除用户前用 */
    public long countInvolving(long userId) {
        List<Map<String, Object>> rows = repo.queryMaps("countInvolving", Map.of("userId", userId));
        if (rows.isEmpty()) {
            return 0;
        }
        Object cnt = rows.get(0).get("cnt");
        return cnt instanceof Number n ? n.longValue() : 0L;
    }

    public SysRelation byId(long id) {
        return repo.findById(id);
    }

    public long create(SysRelation relation) {
        return repo.insert(relation);
    }

    public int delete(long id) {
        return repo.deleteById(id);
    }
}
