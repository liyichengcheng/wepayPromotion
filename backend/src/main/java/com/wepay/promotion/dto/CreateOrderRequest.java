package com.wepay.promotion.dto;

import lombok.Data;

@Data
public class CreateOrderRequest {
    private String userId;
    private Long articleId;
    private Integer payPrice;
    private String parentShareUid;
}
