package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

@Data
public class Article {
    private Long id;
    private Long articleId;
    private String title;
    private Integer basePrice;
    private Integer maxPrice;
    private Date createTime;
}
