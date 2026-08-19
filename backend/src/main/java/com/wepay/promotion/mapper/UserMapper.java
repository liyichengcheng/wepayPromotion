package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.User;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    /**
     * 更新用户实名认证信息 (实名认证通过后调用, 同时将 status 置为 1 已实名)
     * @param openid    分片键
     * @param name      真实姓名
     * @param phoneNo   手机号
     * @param idcardNo  身份证号
     */
    int updateRealNameInfo(@Param("openid") String openid,
                           @Param("name") String name,
                           @Param("phoneNo") String phoneNo,
                           @Param("idcardNo") String idcardNo);

    /**
     * 更新用户状态 (管理员冻结/解冻提现)
     * @param openid  分片键
     * @param status  目标状态: 1=已实名, -1=冻结提现
     */
    int updateStatus(@Param("openid") String openid, @Param("status") Integer status);

    /**
     * 后台按条件查询用户 (不带分片键, ShardingSphere 自动广播查询所有分片)
     * @param phoneNo   手机号 (可空)
     * @param idcardNo  身份证号 (可空)
     * @param status    用户状态 (可空)
     * @return 匹配的用户列表
     */
    List<User> searchUsers(@Param("phoneNo") String phoneNo,
                           @Param("idcardNo") String idcardNo,
                           @Param("status") Integer status);
}
