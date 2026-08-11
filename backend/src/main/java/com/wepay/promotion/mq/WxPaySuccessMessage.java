package com.wepay.promotion.mq;

import lombok.Data;
import java.io.Serializable;

/**
 * 微信支付成功消息 - 用于异步处理佣金
 */
@Data
public class WxPaySuccessMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 订单号 */
    private String orderNo;
    /** 支付者openid */
    private String openid;
    /** 文章ID */
    private Long articleId;
    /** 支付金额(分) */
    private Integer payAmount;
    /** 分享者openid */
    private String parentShareUid;
    /** 微信交易号 */
    private String transactionId;
    /** 支付时间 */
    private Long payTime;
}
