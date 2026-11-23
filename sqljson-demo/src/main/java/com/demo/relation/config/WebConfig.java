package com.demo.relation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
/**
 * MVC 配置：把登录拦截器挂到 {@code /api/**} 上，并放行登录接口与错误页。
 *
 * <p>拦截范围刻意不含静态资源（index.html / login.html）：页面本身不含数据，
 * 真正的数据都在 {@code /api/**} 下。这样未登录时也能看到登录页，
 * 而不是被拦在门口一脸茫然。
 *
 * <p>登录接口本身必须放行，否则会出现「要登录才能登录」的死循环。
 */
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    /**
     * 只拦 {@code /api/**}，静态页面与登录接口放行。
     *
     * <p>这样未登录用户能正常看到登录页，而不是被拦成一个空白页；
     * 数据安全由 API 层保证，页面本身不承载数据。
     */
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录接口本身、静态资源、错误页不需要会话
                .excludePathPatterns("/api/auth/login", "/api/auth/logout", "/error");
    }
}
