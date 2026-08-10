package com.wepay.promotion.service;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.config.WxConfig;
import com.wepay.promotion.dto.WxLoginVO;
import com.wepay.promotion.entity.User;
import com.wepay.promotion.interceptor.AuthInterceptor;
import com.wepay.promotion.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class UserService {

    /** 分享链接模板 */
    public static final String SHARE_LINK_TEMPLATE = "pages/articlePreview/articlePreview?shareUid=%s";

    /** Redis 缓存 key: openid → userId 映射, 用于绕过广播查询实现分片键精准路由 */
    private static final String CACHE_KEY_OPENID_PREFIX = "user:openid:";
    private static final long OPENID_CACHE_TTL_DAYS = 7L;

    private final UserMapper userMapper;
    private final WxPayService wxPayService;
    private final StringRedisTemplate redis;

    public UserService(UserMapper userMapper, WxPayService wxPayService, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.wxPayService = wxPayService;
        this.redis = redis;
    }

    /**
     * 小程序登录: code -> openid -> 创建/查询用户 -> 颁发 token
     *
     * 分片路由策略:
     * 1. 优先查 Redis 缓存 openid → userId, 命中则走 selectByUserId 精准路由 (1库1表)
     *    ShardingSphere standard 策略按 user_id HASH_MOD(4) 自动追加表后缀:
     *      库: ds{N}, 表: t_user_{M}, N,M = user_id.hashCode() % 4
     * 2. Redis 未命中: 走 selectByOpenid 广播查询
     *    ShardingSphere standard 策略的 SELECT 在缺少分片键时, 会自动广播到
     *    4 库 × 4 表 = 16 个物理分片, 自动为逻辑表 t_user 追加后缀:
     *      ds0.t_user_0 / ds0.t_user_1 / ... / ds3.t_user_3
     * 3. 查到用户后回写 Redis 缓存 openid → userId, 后续登录均走精准路由
     */
    public WxLoginVO wxLogin(String code) {
        if (code == null || code.isEmpty()) {
            throw new BusinessException("code不能为空");
        }
        String openid = wxPayService.jsCode2Session(code);
        if (openid == null || openid.isEmpty()) {
            throw new BusinessException("微信登录失败，未获取到openid");
        }

        // 1. 优先查 Redis 缓存, 获取 userId 后走分片键精准路由
        String cacheKey = CACHE_KEY_OPENID_PREFIX + openid;
        String cachedUserId = redis.opsForValue().get(cacheKey);
        User user = null;
        if (cachedUserId != null) {
            user = userMapper.selectByUserId(cachedUserId);
        }

        // 2. Redis 未命中: ShardingSphere standard 策略自动广播到 4 库 16 表
        //    (逻辑表 t_user 被自动重写为 t_user_0 .. t_user_3)
        if (user == null) {
//            user = userMapper.selectByOpenid(openid);
        }

        // 3. 用户不存在则注册
        if (user == null) {
            user = new User();
            user.setUserId(generateUserId());
            user.setOpenid(openid);
            userMapper.insert(user);
            log.info("新用户注册: userId={}, openid={}", user.getUserId(), openid);
        }

        // 4. 回写 Redis 缓存 openid → userId, 后续登录走精准路由无需广播
        redis.opsForValue().set(cacheKey, user.getUserId(), OPENID_CACHE_TTL_DAYS, TimeUnit.DAYS);

        // 生成 token 并缓存(与支付有效期保持一致 30 天)
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("token:" + token, user.getUserId(),
                AuthInterceptor.TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        WxLoginVO vo = new WxLoginVO();
        vo.setUserId(user.getUserId());
        vo.setToken(token);
        vo.setShareLink(buildShareLink(user.getUserId()));
        return vo;
    }

    /**
     * 生成此用户的专属分享链接
     */
    public String buildShareLink(String userId) {
        return String.format(SHARE_LINK_TEMPLATE, userId);
    }

    public User getByUserId(String userId) {
        return userMapper.selectByUserId(userId);
    }

    private String generateUserId() {
        return "u_" + UUID.randomUUID().toString().replace("-", "");
    }
}
