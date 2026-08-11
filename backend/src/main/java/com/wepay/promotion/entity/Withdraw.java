package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

@Data
public class Withdraw {
    private Long id;
    /** 用户openid(分片键) */
    private String openid;
    /** 提现金额(分) */
    private Integer amount;
    /** 0=待处理 1=处理中 2=成功 3=失败 */
    private Integer status;
    private Date applyTime;
    private Date updateTime;
}
