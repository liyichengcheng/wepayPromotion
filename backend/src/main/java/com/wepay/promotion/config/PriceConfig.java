package com.wepay.promotion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文章定价策略配置
 * 对应 application.yml 中 pay.price.* 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "pay.price")
public class PriceConfig {
    /** 基础价(分), 付费人数≤stepCount时此价 */
    private int baseFen = 1;
    /** 封顶价(分) */
    private int maxFen = 5;
    /** 步长: 付费人数≤此值返回基础价, 超过后每增加此值人数加1分 */
    private int stepCount = 2;
}
