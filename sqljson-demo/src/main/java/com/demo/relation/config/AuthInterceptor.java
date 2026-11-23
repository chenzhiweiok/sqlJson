package com.demo.relation.config;

import com.demo.relation.auth.RequireAdmin;
import com.demo.relation.auth.SessionUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jsonsql.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 登录与角色校验，同时把当前用户放进 {@link CurrentUser}，
 * 让审计字段（created_by / updated_by）自动带上操作人。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "SESSION_USER";

    private static final Logger LOG = LoggerFactory.getLogger(AuthInterceptor.class);
    private final ObjectMapper json = new ObjectMapper();

    @Override
    /**
     * 请求进入前的三道检查。
     *
     * <ol>
     *   <li>会话里有没有登录身份——没有就 401，前端据此跳登录页；</li>
     *   <li>方法或类上标了 {@link RequireAdmin} 时，角色必须是 ADMIN——否则 403；</li>
     *   <li>把用户名放进 {@link CurrentUser}，让后续的 insert/update 自动带上审计字段。</li>
     * </ol>
     *
     * <p>返回 false 时不会进入 controller，响应体已经写好，避免再走一遍异常处理。
     *
     * @return true 表示放行
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        SessionUser user = session == null ? null : (SessionUser) session.getAttribute(SESSION_KEY);

        if (user == null) {
            write(response, 401, "请先登录");
            return false;
        }
        if (handler instanceof HandlerMethod method && needsAdmin(method)
                && !user.isAdmin()) {
            LOG.warn("越权访问 {} {} by {}", request.getMethod(), request.getRequestURI(), user.userName());
            write(response, 403, "需要管理员权限");
            return false;
        }
        CurrentUser.set(user.userName());
        return true;
    }

    @Override
    /**
     * 请求结束后清理线程上下文。
     *
     * <p>Servlet 容器复用工作线程，不清理会让下一个请求带着上一个用户的名字写审计字段。
     * 这类串号在日志里看不出来，只有对账时才发现「这条记录不是这个人改的」。
     */
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUser.clear();
    }

    private static boolean needsAdmin(HandlerMethod method) {
        return method.hasMethodAnnotation(RequireAdmin.class)
                || method.getBeanType().isAnnotationPresent(RequireAdmin.class);
    }

    private void write(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("code", code, "msg", msg)));
    }
}
