package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

@Data
public class User {
    private Long id;
    /** 微信openid(分片键) */
    private String openid;
    private String unionId;
    /** 商户系统内部的免确认收款授权单号 (一个用户对应唯一一个生效中的授权) */
    private String outAuthorizationNo;
    /** 用户确认授权后微信支付返回的授权单号 (用于 transferByAuth 接口必填参数) */
    private String authorizationId;
    /** 真实姓名 (实名认证后填写) */
    private String name;
    /** 手机号 (实名认证后填写) */
    private String phoneNo;
    /** 身份证号 (实名认证后填写) */
    private String idcardNo;
    /** 用户状态: 0=未实名, 1=已实名, -1=冻结提现 */
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
