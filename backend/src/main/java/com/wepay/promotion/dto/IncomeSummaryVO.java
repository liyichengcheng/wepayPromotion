package com.wepay.promotion.dto;

import lombok.Data;

@Data
public class IncomeSummaryVO {
    /** 累计收益(元) */
    private String totalIncome;
    /** 每单返佣(元/人) */
    private String todayIncome;
    /** 可提现金额(元) */
    private String withdrawable;
    /** 推广人数 */
    private Integer todayCount;
}
