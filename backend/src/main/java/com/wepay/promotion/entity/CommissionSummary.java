package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

/**
 * 佣金汇总 - 按用户维度汇总佣金数据
 */
@Data
public class CommissionSummary {
    private Long id;
    /** 用户ID(分片键) */
    private String userId;
    /** 用户openid(用于提现转账) */
    private String openid;
    /** 总佣金额(分) */
    private Integer totalAmount;
    /** 提现中佣金额(分) */
    private Integer pendingAmount;
    /** 已提现佣金额(分) */
    private Integer withdrawnAmount;
    private Date createTime;
    private Date updateTime;
}
