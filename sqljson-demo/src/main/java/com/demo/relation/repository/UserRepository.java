package com.demo.relation.repository;

import com.demo.relation.entity.SysUser;
import io.github.jsonsql.PageResult;
import io.github.jsonsql.SqlJsonRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户仓储：只做「参数整理 + 调用框架」，不含业务规则。
 *
 * <p>SQL 全在 resources/sqls/sys_user.json 与框架生成的默认语句里。
 * 因为实体声明了 {@code @SqlColumn(deleted = true)}，这里所有查询都会自动带
 * {@code deleted = 0}，删除会改写为 update——所以仓储代码里看不到任何 deleted 判断。
 */
@Repository
public class UserRepository {

    private final SqlJsonRepository<SysUser> repo;

    public UserRepository(@Qualifier("userSqlJsonRepository") SqlJsonRepository<SysUser> repo) {
        this.repo = repo;
    }

    public PageResult<SysUser> page(String keyword, String dept, String status, int pageNo, int size) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", blankToNull(keyword));
        args.put("dept", blankToNull(dept));
        args.put("status", blankToNull(status));
        return repo.page("findPage", pageNo, size, args);
    }

    public SysUser byId(long id) {
        return repo.findById(id);
    }

    /** 登录用：按用户名查，含密码哈希 */
    public SysUser byName(String userName) {
        List<SysUser> list = repo.query("findByUserName", Map.of("userName", userName));
        return list.isEmpty() ? null : list.get(0);
    }

    public List<SysUser> all() {
        return repo.findAll();
    }

    /**
     * 按状态计数。
     *
     * <p>用框架的 {@code countBy}：字段名由实体元数据校验成列名、值走参数绑定，
     * 逻辑删除过滤自动带上，所以口径与列表页的分页总数一致。
     */
    public long countByStatus(String status) {
        return repo.countBy("status", status);
    }

    /** 全量计数（不含已删） */
    public long countAll() {
        return repo.countAll();
    }

    public long create(SysUser user) {
        return repo.insert(user);
    }

    public int update(SysUser user) {
        return repo.updateById(user);
    }

    /** 逻辑删除：框架会改写成 update ... set deleted = 1 */
    public int delete(long id) {
        return repo.deleteById(id);
    }

    /**
     * 事务透传：级联操作要跨两个仓库，需要同一个连接。
     *
     * <p>框架的 {@code tx} 按线程共享连接，所以这里只要把两个仓库的调用放进同一个块，
     * 它们就自然在同一个事务里——不需要传连接，也不需要分布式事务。
     */
    public void tx(Runnable body) {
        repo.tx(body);
    }

    /** 回收站列表：语句里显式写了 deleted = 1，框架不会再加 deleted = 0 */
    public List<Map<String, Object>> deleted(int limit) {
        return repo.queryMaps("recycleList", Map.of("limit", limit));
    }

    public int restore(long id) {
        return repo.restoreById(id);
    }

    public List<Map<String, Object>> countByDept() {
        return repo.pageMaps("countByDept", 1, 100, Map.of()).list();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
