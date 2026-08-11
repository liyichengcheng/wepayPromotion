package com.wepay.promotion.interceptor;

import com.wepay.promotion.common.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final long TOKEN_TTL_SECONDS = 30 * 24 * 3600L;
    public static final String CURRENT_OPENID = "currentOpenid";

    private final StringRedisTemplate redis;

    public AuthInterceptor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            throw new BusinessException(401, "未登录");
        }
        String key = "token:" + token;
        String openid = redis.opsForValue().get(key);
        if (openid == null) {
            throw new BusinessException(401, "登录已过期，请重新登录");
        }
        // 续期
        redis.expire(key, TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        request.setAttribute(CURRENT_OPENID, openid);
        return true;
    }
}
