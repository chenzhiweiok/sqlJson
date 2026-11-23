package com.demo.relation.web;

import com.demo.relation.auth.AuthService;
import com.demo.relation.auth.SessionUser;
import com.demo.relation.config.AuthInterceptor;
import com.demo.relation.service.BizException;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
/**
 * 登录相关接口：登录、登出、当前身份。
 *
 * <p>身份存在 {@link jakarta.servlet.http.HttpSession} 里，会话 cookie 由容器维护
 * （HttpOnly + SameSite=Lax，配置见 application.yml）。
 *
 * <p>这是演示级实现：够用来展示「会话 + 角色控制菜单」这条链路，
 * 但<b>不具备生产强度</b>——没有 CSRF 防护、没有登录失败限流、密码用固定盐 SHA-256。
 * 真上生产请换 Spring Security，或至少补上这三项。
 */
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginReq(String userName, String password) {
    }

    /**
     * 登录。
     *
     * <p>成功后把身份写进 session；失败统一返回 401「用户名或密码不正确」，
     * <b>不区分「账号不存在」与「密码错误」</b>——两种提示会给撞库提供账号枚举能力。
     *
     * @return {@code {id, userName, role}}，前端据此决定菜单与按钮
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req, HttpSession session) {
        SessionUser user = auth.login(req.userName(), req.password());
        session.setAttribute(AuthInterceptor.SESSION_KEY, user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.id());
        body.put("userName", user.userName());
        body.put("role", user.role());
        return body;
    }

    /**
     * 登出：销毁会话。
     *
     * <p>只清服务端 session；cookie 由浏览器按过期时间自然淘汰。演示够用，
     * 生产还应考虑「同时踢掉其他设备」与 CSRF token 的失效。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Map.of("ok", true);
    }

    /**
     * 当前登录身份。
     *
     * <p>前端首屏调它：200 说明会话有效，401 就跳登录页。这是「页面不闪一下未登录状态」
     * 的最省事做法，比让每个数据接口各自处理 401 更清晰。
     */
    @GetMapping("/me")
    public SessionUser me(HttpSession session) {
        SessionUser user = (SessionUser) session.getAttribute(AuthInterceptor.SESSION_KEY);
        if (user == null) {
            throw new BizException(401, "未登录");
        }
        return user;
    }
}
