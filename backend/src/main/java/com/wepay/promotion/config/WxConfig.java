package com.wepay.promotion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "wx")
public class WxConfig {
    private MiniApp miniapp;
    private Pay pay;
    private Share share;

    @Data
    public static class MiniApp {
        private String appid;
        private String secret;
    }

    @Data
    public static class Pay {
        private String mchId;
        private String mchKey;
        private String notifyUrl;
        private String certPath;
        private String transferDesc;
        /**
         * 开发环境Mock模式:
         * 当微信商户尚未开通「企业付款到零钱」导致 NO_AUTH 时,
         * 设为 true 可模拟微信返回成功, 让主流程跑通以便调试.
         * 生产环境务必保持 false/不填.
         */
        private Boolean mockTransfer = Boolean.FALSE;
    }

    @Data
    public static class Share {
        private String aesKey;
    }
}