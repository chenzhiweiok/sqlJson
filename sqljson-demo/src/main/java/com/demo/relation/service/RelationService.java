package com.demo.relation.service;

import com.demo.relation.entity.SysRelation;
import com.demo.relation.entity.SysUser;
import com.demo.relation.repository.RelationRepository;
import com.demo.relation.repository.UserRepository;
import io.github.jsonsql.PageResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系业务规则：不能自连、类型受限、目标必须存在、不能重复。
 * 图谱与统计的组装也在这里，Web 层只负责搬数据。
 */
@Service
public class RelationService {

    public static final Set<String> TYPES = Set.of("FOLLOW", "FRIEND", "BLOCK");

    private final RelationRepository relations;
    private final UserRepository users;

    public RelationService(RelationRepository relations, UserRepository users) {
        this.relations = relations;
        this.users = users;
    }

    /**
     * 关系分页。
     *
     * @param userId  只看某个用户发起的边，null 表示全部
     * @param relType 关系类型，null 表示全部
     */
    public PageResult<SysRelation> page(Long userId, String relType, int pageNo, int size) {
        if (pageNo < 1) {
            pageNo = 1;
        }
        if (size < 1 || size > 100) {
            size = 10;
        }
        return relations.page(userId, relType, pageNo, size);
    }

    /**
     * 某用户的全部关系（含目标用户信息），直接给前端渲染，不做分页。
     *
     * <p>一个用户的关系数通常只有几十条，分页反而增加交互成本；
     * 如果确实可能出现上千条，应改成按类型分页查询。
     */
    public List<Map<String, Object>> ofUser(long userId) {
        return relations.ofUser(userId);
    }

    /**
     * 建立关系，四条规则缺一不可。
     *
     * <ul>
     *   <li>不能与自己建立；</li>
     *   <li>类型只能是 FOLLOW / FRIEND / BLOCK（大小写不敏感，会转大写）；</li>
     *   <li>两端用户都必须存在且未被删除；</li>
     *   <li>同方向同类型不能重复（反向不算重复，A 关注 B 与 B 关注 A 是两条边）。</li>
     * </ul>
     *
     * <p>这些规则<b>不放在数据库唯一索引上</b>，是刻意的：唯一索引只能拦住「完全重复」，
     * 拦不住「和自己建关系」；而且把规则放在代码里，报错信息才能是给人看的
     * （「不能和自己建立关系」而不是「Duplicate entry」）。
     *
     * @return 新关系的主键
     * @throws BizException 400 规则不满足；404 用户不存在
     */
    public long create(long userId, long targetId, String relType) {
        if (userId == targetId) {
            throw new BizException("不能和自己建立关系");
        }
        String type = relType == null ? "" : relType.trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new BizException("关系类型只能是 FOLLOW / FRIEND / BLOCK");
        }
        if (users.byId(userId) == null) {
            throw new BizException(404, "发起用户不存在: " + userId);
        }
        if (users.byId(targetId) == null) {
            throw new BizException(404, "目标用户不存在: " + targetId);
        }
        if (relations.exists(userId, targetId, type)) {
            throw new BizException("这条关系已存在");
        }
        SysRelation r = new SysRelation();
        r.setUserId(userId);
        r.setTargetId(targetId);
        r.setRelType(type);
        return relations.create(r);
    }

    /**
     * 解除一条关系。
     *
     * <p>关系表不启用逻辑删除：它是纯关联数据，删了就是删了，没有「恢复一条关注」
     * 这种业务诉求；保留逻辑删除列反而会让唯一索引失效。
     *
     * @throws BizException 404 关系不存在
     */
    public void delete(long id) {
        if (relations.byId(id) == null) {
            throw new BizException(404, "关系不存在: " + id);
        }
        relations.delete(id);
    }

    /** 图谱数据：节点带度数，边带类型，前端直接画。 */
    public Map<String, Object> graph() {
        List<SysUser> all = users.all();
        List<Map<String, Object>> rawEdges = relations.graph();

        Map<Long, Integer> degree = new HashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> row : rawEdges) {
            long from = toLong(row.get("user_id"));
            long to = toLong(row.get("target_id"));
            degree.merge(from, 1, Integer::sum);
            degree.merge(to, 1, Integer::sum);

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("source", from);
            e.put("target", to);
            e.put("type", row.get("rel_type"));
            edges.add(e);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (SysUser u : all) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", u.getId());
            n.put("name", u.getUserName());
            n.put("dept", u.getDept());
            n.put("status", u.getStatus());
            n.put("degree", degree.getOrDefault(u.getId(), 0));
            nodes.add(n);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    /**
     * 统计面板数据：关系类型分布、关系最多的用户、部门人数。
     *
     * <p>三次独立查询在 service 里合并成一个响应，省掉前端连发三个请求；
     * 三条 SQL 都是聚合查询，走的是 {@code queryMaps} 而不是实体映射。
     */
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("types", relations.statsByType());
        result.put("top", relations.topUsers(8));
        result.put("depts", users.countByDept());
        return result;
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }
}
