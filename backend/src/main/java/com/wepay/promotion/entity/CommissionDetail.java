package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

/**
 * 佣金明细 - 记录每笔佣金的详细信息
 */
@Data
public class CommissionDetail {
    private Long id;
    /** 获利者user_id(分片键) */
    private String userId;
    /** 获利者openid */
    private String openid;
    /** 支付者user_id */
    private String fromUserId;
    /** 订单号 */
    private String orderNo;
    /** 订单支付金额(分) */
    private Integer payAmount;
    /** 佣金金额(分) 30%最低2元 */
    private Integer commissionAmount;
    private Date createTime;
    private Date transferTime;
}
