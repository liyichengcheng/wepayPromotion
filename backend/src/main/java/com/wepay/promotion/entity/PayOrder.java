package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

@Data
public class PayOrder {
    private Long id;
    private String orderNo;
    private String userId;
    private String openid;
    private Long articleId;
    private Integer payPrice;
    private String parentShareUid;
    private Integer status;
    private String prepayId;
    private String transactionId;
    private Date payTime;
    private Date createTime;
    private Date updateTime;
}
