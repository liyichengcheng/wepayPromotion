package com.wepay.promotion.dto;

import lombok.Data;

@Data
public class PayInfoVO {
    private String timeStamp;
    private String nonceStr;
    private String packageX;
    private String signType;
    private String paySign;
}
