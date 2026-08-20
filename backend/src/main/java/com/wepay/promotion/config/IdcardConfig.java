package com.wepay.promotion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实名认证配置
 * 对应 application.yml 中 idcard.* 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "idcard")
public class IdcardConfig {
    /** 身份证图片存储目录 */
    private String uploadDir;
    /** 实名认证阈值(分): 累计提现达此值时强制实名 */
    private int realNameThresholdFen;
    /** 客服微信号 (用户提交实名信息后提示添加) */
    private String customerWechat;
}
