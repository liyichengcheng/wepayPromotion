package com.wepay.promotion.service;

import com.wepay.promotion.common.BusinessException;
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
     * openid 即为分片键, selectByOpenid 携带分片键实现精准路由 (1库1表)
     * ShardingSphere standard 策略按 openid HASH_MOD(4) 自动追加表后缀:
     *   库: ds{N}, 表: t_user_{M}, N,M = openid.hashCode() % 4
     */
    public WxLoginVO wxLogin(String code) {
        if (code == null || code.isEmpty()) {
            throw new BusinessException("code不能为空");
        }
        String openid = wxPayService.jsCode2Session(code);
        if (openid == null || openid.isEmpty()) {
            throw new BusinessException("微信登录失败，未获取到openid");
        }

        // openid 是分片键, 直接精准路由查询
        User user = userMapper.selectByOpenid(openid);

        // 用户不存在则注册
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            userMapper.insert(user);
            log.info("新用户注册: openid={}", openid);
        }

        // 生成 token 并缓存 openid (与支付有效期保持一致 30 天)
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("token:" + token, openid,
                AuthInterceptor.TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        WxLoginVO vo = new WxLoginVO();
        vo.setOpenid(openid);
        vo.setToken(token);
        vo.setShareLink(buildShareLink(openid));
        return vo;
    }

    /**
     * 生成此用户的专属分享链接
     */
    public String buildShareLink(String openid) {
        return String.format(SHARE_LINK_TEMPLATE, openid);
    }

    public User getByOpenid(String openid) {
        return userMapper.selectByOpenid(openid);
    }
}
