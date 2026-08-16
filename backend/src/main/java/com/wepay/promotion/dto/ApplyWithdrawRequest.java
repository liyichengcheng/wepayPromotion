package com.wepay.promotion.dto;

import lombok.Data;

/**
 * 申请提现请求
 */
@Data
public class ApplyWithdrawRequest {
    /** 提现金额(分), 最小100(1元) */
    private Integer amount;
}
