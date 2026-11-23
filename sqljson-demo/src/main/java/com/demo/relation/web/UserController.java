package com.demo.relation.web;

import com.demo.relation.auth.RequireAdmin;
import com.demo.relation.entity.SysUser;
import com.demo.relation.service.UserService;
import io.github.jsonsql.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
/**
 * 用户接口，全部走 JSON。
 *
 * <table border="1">
 *   <caption>路径与权限</caption>
 *   <tr><th>方法</th><th>路径</th><th>作用</th><th>权限</th></tr>
 *   <tr><td>GET</td><td>/api/users</td><td>分页查询</td><td>登录</td></tr>
 *   <tr><td>GET</td><td>/api/users/all</td><td>全量（下拉选项）</td><td>登录</td></tr>
 *   <tr><td>GET</td><td>/api/users/{id}</td><td>单条</td><td>登录</td></tr>
 *   <tr><td>POST</td><td>/api/users</td><td>新建</td><td>登录</td></tr>
 *   <tr><td>PUT</td><td>/api/users/{id}</td><td>更新（只改非空字段）</td><td>登录</td></tr>
 *   <tr><td>DELETE</td><td>/api/users/{id}</td><td>逻辑删除</td><td>管理员</td></tr>
 *   <tr><td>GET</td><td>/api/users/recycle</td><td>回收站</td><td>管理员</td></tr>
 *   <tr><td>POST</td><td>/api/users/{id}/restore</td><td>恢复</td><td>管理员</td></tr>
 * </table>
 *
 * <p><b>删除是逻辑删除</b>：记录仍在表里，进入回收站，可随时恢复。
 * 这是框架层的能力（{@code @SqlColumn(deleted = true)}），controller 只负责转发，
 * 这里看不到任何 {@code deleted} 判断——如果看到了，说明逻辑漏到不该在的层。
 *
 * <p>管理员校验用 {@link com.demo.relation.auth.RequireAdmin}，由拦截器统一执行；
 * 方法体里不写 {@code if (user.isAdmin())}，是为了让「权限规则」只有一个出处。
 */
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public PageResult<SysUser> page(@RequestParam(name = "keyword", required = false) String keyword,
                                    @RequestParam(name = "dept", required = false) String dept,
                                    @RequestParam(name = "status", required = false) String status,
                                    @RequestParam(name = "page", defaultValue = "1") int page,
                                    @RequestParam(name = "size", defaultValue = "10") int size) {
        return users.page(keyword, dept, status, page, size);
    }

    /** 下拉选项用：不分页全量（演示数据量小；真实项目应限制条数） */
    @GetMapping("/all")
    public List<SysUser> all() {
        return users.all();
    }

    @GetMapping("/{id}")
    public SysUser get(@PathVariable("id") long id) {
        return users.get(id);
    }

    /**
     * 新建用户。
     *
     * <p>密码由 {@link com.demo.relation.auth.AuthService} 的规则生成，
     * 这里只接受姓名/邮箱/部门/状态；新用户默认角色 {@code MEMBER}、默认密码 {@code 123456}
     * （演示约定，生产应由管理员设置初始密码并强制首次登录修改）。
     *
     * @return {@code {"id": 主键}}
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody SysUser user) {
        long id = users.create(user);
        return Map.of("id", id);
    }

    /**
     * 更新用户，<b>只改传了值的字段</b>。
     *
     * <p>前端可以只提交变化的部分，不需要先把整条记录查出来再回填——
     * 这是框架「只注入非空字段」带来的语义，不是这里做了特殊处理。
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") long id, @RequestBody SysUser user) {
        users.update(id, user);
        return Map.of("ok", true);
    }

    /**
     * 删除用户（管理员）。
     *
     * <p><b>逻辑删除</b>：把 {@code deleted} 置 1，记录仍在表里，可从回收站恢复。
     * 有关联关系时由 service 拦下并返回 400——先断关系再删人，是业务规则而不是外键约束。
     */
    @DeleteMapping("/{id}")
    @RequireAdmin
    public Map<String, Object> delete(@PathVariable("id") long id) {
        users.delete(id);
        return Map.of("ok", true);
    }

    /**
     * 回收站列表（管理员）。
     *
     * <p>返回 {@code Map} 而不是实体：这里要展示「删除时间、操作人」这类审计信息，
     * 用 Map 更直接；也因为这条查询显式绕过了逻辑删除过滤（语句里写的是 {@code deleted = 1}）。
     *
     * @param limit 条数上限，默认 50
     */
    @GetMapping("/recycle")
    @RequireAdmin
    public List<Map<String, Object>> recycle(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return users.deleted(limit);
    }

    /**
     * 从回收站恢复（管理员）。
     *
     * <p>把 {@code deleted} 置回 0，同时刷新 updated_at / updated_by，
     * 所以恢复动作本身也会留下审计痕迹。
     *
     * @return {@code {"ok": true, "rows": n}}；记录不存在或未被删除时 {@code ok=false}
     */
    @PostMapping("/{id}/restore")
    @RequireAdmin
    public Map<String, Object> restore(@PathVariable("id") long id) {
        int rows = users.restore(id);
        return Map.of("ok", rows > 0, "rows", rows);
    }
}
