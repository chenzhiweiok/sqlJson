package com.demo.relation.auth;

import com.demo.relation.entity.SysUser;
import com.demo.relation.repository.UserRepository;
import com.demo.relation.service.BizException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 极简登录服务。
 *
 * <p>密码用 SHA-256(salt + 明文) 存储——演示够用，但**不适合生产**：
 * 没有迭代、没有随机盐、没有时序防护。真实项目请用 BCrypt / Argon2。
 */
@Service
public class AuthService {

    /** 演示用固定盐；生产必须每个用户一个随机盐 */
    private static final String SALT = "sqljson-demo";

    private final UserRepository users;

    public AuthService(UserRepository users) {
        this.users = users;
    }

    /**
     * 校验账号密码，返回可放进会话的身份。
     *
     * <p>三件事按顺序做：查用户（自动过滤已逻辑删除的）、比对哈希、检查账号状态。
     * 「用户不存在」与「密码错误」返回同一句提示，避免被用来枚举账号——
     * 这类细节在渗透测试里是第一个被利用的点。
     *
     * @param userName 用户名
     * @param password 明文密码
     * @return 会话身份（id / 用户名 / 角色）
     * @throws BizException 400 参数为空、401 凭证错误、403 账号已停用
     */
    public SessionUser login(String userName, String password) {
        if (userName == null || userName.isBlank() || password == null || password.isEmpty()) {
            throw new BizException(400, "用户名和密码不能为空");
        }
        SysUser user = users.byName(userName.trim());
        // 用户不存在与密码错误返回同一句，避免撞库时被用来枚举账号
        if (user == null || !hash(password).equals(user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码不正确");
        }
        if ("INACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BizException(403, "该账号已停用，请联系管理员");
        }
        return new SessionUser(user.getId(), user.getUserName(),
                user.getRole() == null ? "MEMBER" : user.getRole());
    }

    /**
     * 计算密码摘要。
     *
     * <p>算法是 {@code SHA-256(固定盐 + 明文)}，<b>只适合演示</b>：
     * 固定盐意味着同一密码在所有部署里哈希相同，可被彩虹表直接命中；
     * 单轮哈希也扛不住 GPU 暴力破解。生产请用 BCrypt / Argon2（自带随机盐与迭代）。
     *
     * @param raw 明文密码
     * @return 64 位十六进制摘要
     */
    public static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((SALT + raw).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
