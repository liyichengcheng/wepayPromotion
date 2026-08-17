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
         * 当微信商户尚未开通「商家转账到零钱」导致 NO_AUTH 时,
         * 设为 true 可模拟微信返回成功, 让主流程跑通以便调试.
         * 生产环境务必保持 false/不填.
         */
        private Boolean mockTransfer = Boolean.FALSE;

        // ======= V3 商家转账到零钱 必填配置 =======
        /** APIv3 密钥 (32 字节字符串), 用于回调/敏感字段解密 */
        private String v3Key;
        /** 商户 API 私钥路径 apiclient_key.pem (V3), 用于签 Authorization 头 */
        private String privateKeyPath;
        /** 商户 API 证书序列号, 从 apiclient_cert.pem 中读取或在商户平台证书管理页查看 */
        private String merchantSerial;
        /** 微信支付平台证书/公钥路径 wechatpay.pem, 用于校验响应签名 */
        private String platformCertPath;
    }

    @Data
    public static class Share {
        private String aesKey;
    }
}