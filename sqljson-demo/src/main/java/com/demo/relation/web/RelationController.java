package com.demo.relation.web;

import com.demo.relation.entity.SysRelation;
import com.demo.relation.service.RelationService;
import io.github.jsonsql.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
/**
 * 关系接口：分页、按用户查、建立、解除、图谱、统计。
 *
 * <p>业务规则（不能自连、不能重复、目标必须存在）都在
 * {@link com.demo.relation.service.RelationService} 里，
 * 这一层只做三件事：接参数、调服务、把结果交给 Jackson。
 *
 * <p>图谱与统计返回的是 {@code Map} 而不是实体——它们是多表聚合的结果，
 * 没有对应的表，硬造实体只会多出一堆 {@code @SqlColumn(skip = true)} 的字段。
 */
public class RelationController {

    private final RelationService relations;

    public RelationController(RelationService relations) {
        this.relations = relations;
    }

    public record CreateReq(long userId, long targetId, String relType) {
    }

    @GetMapping("/relations")
    public PageResult<SysRelation> page(@RequestParam(name = "userId", required = false) Long userId,
                                        @RequestParam(name = "relType", required = false) String relType,
                                        @RequestParam(name = "page", defaultValue = "1") int page,
                                        @RequestParam(name = "size", defaultValue = "10") int size) {
        return relations.page(userId, relType, page, size);
    }

    @GetMapping("/relations/user/{userId}")
    public List<Map<String, Object>> ofUser(@PathVariable("userId") long userId) {
        return relations.ofUser(userId);
    }

    /**
     * 建立关系。
     *
     * <p>四条规则在 {@link com.demo.relation.service.RelationService#create} 里：
     * 不能与自己建立、类型必须合法、两端用户必须存在、同方向同类型不可重复。
     * 违反任一条都会抛 {@code BizException}，由全局异常处理器转成 400/404。
     *
     * @return {@code {"id": 新关系主键}}
     */
    @PostMapping("/relations")
    public Map<String, Object> create(@RequestBody CreateReq req) {
        long id = relations.create(req.userId(), req.targetId(), req.relType());
        return Map.of("id", id);
    }

    @DeleteMapping("/relations/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        relations.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/graph")
    public Map<String, Object> graph() {
        return relations.graph();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return relations.stats();
    }
}
