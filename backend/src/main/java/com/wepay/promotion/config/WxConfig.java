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
    }
}
