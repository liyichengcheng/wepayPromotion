package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.User;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {
    /**
     * 按openid查询用户(分片键精准路由)
     */
    User selectByOpenid(@Param("openid") String openid);

    int insert(User user);

    /**
     * 更新用户的免确认收款授权信息 (申请授权成功后调用)
     * @param openid              分片键
     * @param outAuthorizationNo  商户侧授权单号
     */
    int updateAuthorizationNo(@Param("openid") String openid,
                              @Param("outAuthorizationNo") String outAuthorizationNo);

    /**
     * 更新用户的微信授权单号 (用户确认授权后, 微信异步通知触发)
     * @param openid           分片键
     * @param authorizationId  微信返回的授权单号
     */
    int updateAuthorizationId(@Param("openid") String openid,
                              @Param("authorizationId") String authorizationId);

    /**
     * 清除用户的免确认收款授权 (用户解除授权或授权被关闭后调用)
     * @param openid 分片键
     */
    int clearAuthorization(@Param("openid") String openid);
}
