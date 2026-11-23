package com.demo.relation.service;

import com.demo.relation.entity.SysUser;
import com.demo.relation.repository.RelationRepository;
import com.demo.relation.repository.UserRepository;
import io.github.jsonsql.PageResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户业务规则。这一层不出现任何 SQL 概念，只处理"能不能这么做"。
 */
@Service
public class UserService {

    private static final Set<String> STATUS = Set.of("ACTIVE", "INACTIVE");

    private final UserRepository users;
    private final RelationRepository relations;

    public UserService(UserRepository users, RelationRepository relations) {
        this.users = users;
        this.relations = relations;
    }

    /**
     * 分页查询，顺带做参数兜底。
     *
     * <p>页码小于 1 按 1 处理、每页超过 100 按 10 处理——这类修正放在 service 而不是
     * controller，是为了让「从别的入口（定时任务、MQ 消费者）调用」时也有同样的保护。
     *
     * @param keyword 姓名模糊匹配，null 或空串表示不限
     * @param dept    部门精确匹配，null 表示不限
     * @param status  ACTIVE / INACTIVE，null 表示不限
     */
    public PageResult<SysUser> page(String keyword, String dept, String status, int pageNo, int size) {
        if (pageNo < 1) {
            pageNo = 1;
        }
        if (size < 1 || size > 100) {
            size = 10;
        }
        return users.page(keyword, dept, status, pageNo, size);
    }

    /**
     * 按主键取用户。
     *
     * @param id 主键
     * @return 实体
     * @throws BizException 404 用户不存在或已被逻辑删除
     */
    public SysUser get(long id) {
        SysUser u = users.byId(id);
        if (u == null) {
            throw new BizException(404, "用户不存在: " + id);
        }
        return u;
    }

    /**
     * 新建用户，并做三项校验。
     *
     * <ol>
     *   <li>姓名不能为空（会 trim 掉首尾空格）；</li>
     *   <li>邮箱非空时必须包含 {@code @}——只是最低限度的形态检查，不做正则强校验，
     *       因为过严的正则会误杀合法地址；</li>
     *   <li>状态只能是 ACTIVE / INACTIVE，不传默认 ACTIVE。</li>
     * </ol>
     *
     * <p>密码与角色不在这里设置：新用户统一走默认值（123456 / MEMBER），
     * 由 {@link com.demo.relation.auth.AuthService} 的规则负责，避免「建用户时顺手设密码」
     * 这种把凭证散落在业务代码里的做法。
     *
     * @return 数据库生成的主键
     * @throws BizException 400 校验不通过
     */
    public long create(SysUser user) {
        if (user.getUserName() == null || user.getUserName().isBlank()) {
            throw new BizException("姓名不能为空");
        }
        if (user.getEmail() != null && !user.getEmail().isBlank() && !user.getEmail().contains("@")) {
            throw new BizException("邮箱格式不正确");
        }
        if (user.getStatus() == null || user.getStatus().isBlank()) {
            user.setStatus("ACTIVE");
        }
        if (!STATUS.contains(user.getStatus())) {
            throw new BizException("状态只能是 ACTIVE 或 INACTIVE");
        }
        user.setUserName(user.getUserName().trim());
        return users.create(user);
    }

    /**
     * 更新用户（部分更新）。
     *
     * <p>先确认记录存在，再把 id 覆盖到 patch 上——防止调用方漏传 id 导致更新了别的行，
     * 或者在路径 id 与 body id 不一致时产生歧义（以路径为准）。
     *
     * <p>只更新 patch 里非空的字段，所以「改状态」不需要先查出来再整体赋值。
     *
     * @param id    路径上的主键，优先级高于 body
     * @param patch 只包含要改的字段
     * @throws BizException 404 用户不存在；400 状态值非法
     */
    public void update(long id, SysUser patch) {
        SysUser exists = get(id);
        patch.setId(exists.getId());
        if (patch.getStatus() != null && !STATUS.contains(patch.getStatus())) {
            throw new BizException("状态只能是 ACTIVE 或 INACTIVE");
        }
        users.update(patch);
    }

    /** 有关联关系时不允许直接删除，先断关系再删人——这是业务规则，不是数据库约束。 */
    public void delete(long id) {
        get(id);
        long relCount = relations.countInvolving(id);
        if (relCount > 0) {
            throw new BizException("该用户还有 " + relCount + " 条关系（含入边与出边），请先解除关系");
        }
        users.delete(id);
    }

    /**
     * 全量用户，给下拉选项用。
     *
     * <p>没有分页和上限：只适合演示数据量。真实项目应改成「按关键字搜索前 20 条」，
     * 否则用户表一大，这个接口就会拖垮页面。
     */
    public List<SysUser> all() {
        return users.all();
    }

    public List<Map<String, Object>> deptStats() {
        return users.countByDept();
    }

    /** 回收站：已逻辑删除的用户 */
    public List<Map<String, Object>> deleted(int limit) {
        int n = limit < 1 || limit > 200 ? 50 : limit;
        return users.deleted(n);
    }

    /** 从回收站恢复 */
    public int restore(long id) {
        int rows = users.restore(id);
        if (rows == 0) {
            throw new BizException(404, "回收站里没有这个用户: " + id);
        }
        return rows;
    }
}
