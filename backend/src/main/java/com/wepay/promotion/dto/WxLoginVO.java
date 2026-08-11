package com.wepay.promotion.dto;

import lombok.Data;

@Data
public class WxLoginVO {
    private String openid;
    private String token;
    /** 此用户的专属分享链接路径 */
    private String shareLink;
}
