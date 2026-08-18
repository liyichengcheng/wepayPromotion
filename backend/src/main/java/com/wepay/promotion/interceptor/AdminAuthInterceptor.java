package com.wepay.promotion.interceptor;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.service.AdminPasswordService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 后台管理接口鉴权拦截器
 * 使用 HTTP Basic Auth, 密码优先从Redis读取 (支持在线修改), 否则读取application.yml
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final AdminPasswordService passwordService;

    public AdminAuthInterceptor(AdminPasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            throw new BusinessException(401, "管理员接口需认证");
        }
        String base64Credentials = authHeader.substring(6).trim();
        String decoded;
        try {
            decoded = new String(java.util.Base64.getDecoder().decode(base64Credentials));
        } catch (Exception e) {
            throw new BusinessException(401, "认证信息格式错误");
        }
        String[] parts = decoded.split(":", 2);
        if (parts.length != 2) {
            throw new BusinessException(401, "认证信息格式错误");
        }
        if (!"admin".equals(parts[0]) || !passwordService.verifyPassword(parts[1])) {
            throw new BusinessException(403, "管理员密码错误");
        }
        return true;
    }
}
