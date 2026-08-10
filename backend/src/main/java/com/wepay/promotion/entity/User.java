package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

@Data
public class User {
    private Long id;
    private String userId;
    private String openid;
    private String unionId;
    private Date createTime;
    private Date updateTime;
}
