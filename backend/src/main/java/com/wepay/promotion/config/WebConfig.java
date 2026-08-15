package com.wepay.promotion.config;

import com.wepay.promotion.interceptor.AdminAuthInterceptor;
import com.wepay.promotion.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, AdminAuthInterceptor adminAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用户端鉴权 (token)
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/wxLogin",
                        "/pay/notify",
                        "/article/getPayTotal",
                        "/admin/**",        // 静态管理界面
                        "/api/admin/**",     // 管理API(由AdminAuthInterceptor单独拦截)
                        "/error"
                );

        // 管理员端鉴权 (Basic Auth) - 拦截 API, 放行静态资源
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns(
                        "/admin/*.html",
                        "/admin/css/**",
                        "/admin/js/**",
                        "/admin/img/**"
                );
    }
}