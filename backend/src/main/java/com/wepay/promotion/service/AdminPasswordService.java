package com.wepay.promotion.service;

import com.wepay.promotion.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/**
 * 管理员密码加密/解密 + 存储
 *  - 启动时自动生成 RSA-2048 密钥对
 *  - 前端用 RSA 公钥加密新密码, 后端用私钥解密后写入 Redis
 *  - 密码校验优先级: Redis > application.yml
 */
@Slf4j
@Service
public class AdminPasswordService {

    private static final String REDIS_KEY = "admin:password";

    private final StringRedisTemplate redis;
    private final String defaultPwd;

    private PrivateKey privateKey;
    private String publicKeyPem;

    public AdminPasswordService(StringRedisTemplate redis,
                                @Value("${admin.password:admin123}") String defaultPwd) {
        this.redis = redis;
        this.defaultPwd = defaultPwd;
    }

    @PostConstruct
    public void init() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            this.privateKey = kp.getPrivate();
            byte[] pubEnc = kp.getPublic().getEncoded();
            String raw = Base64.getEncoder().encodeToString(pubEnc);
            StringBuilder pem = new StringBuilder();
            pem.append("-----BEGIN PUBLIC KEY-----\n");
            for (int i = 0; i < raw.length(); i += 64) {
                pem.append(raw, i, Math.min(i + 64, raw.length())).append("\n");
            }
            pem.append("-----END PUBLIC KEY-----");
            this.publicKeyPem = pem.toString();
            log.info("AdminPasswordService RSA key pair initialized");
        } catch (Exception e) {
            log.error("RSA 密钥对生成失败", e);
            throw new RuntimeException("RSA key gen failed", e);
        }
    }

    /** 获取 PEM 格式公钥 (前端 JSEncrypt 可直接使用) */
    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    /** 获取当前有效的管理员密码: Redis 优先, 否则默认配置 */
    public String getEffectivePassword() {
        try {
            String pwd = redis.opsForValue().get(REDIS_KEY);
            if (pwd != null && !pwd.isEmpty()) {
                return pwd;
            }
        } catch (Exception e) {
            log.warn("从Redis读取管理员密码失败, 回落到配置", e);
        }
        return defaultPwd;
    }

    /**
     * 修改管理员密码
     * @param oldPassword 原密码 (明文校验)
     * @param encryptedNewPassword RSA加密后的新密码 (Base64)
     */
    public synchronized void changePassword(String oldPassword, String encryptedNewPassword) {
        String current = getEffectivePassword();
        if (!current.equals(oldPassword)) {
            throw new BusinessException("原密码错误");
        }
        String newPwd = decryptPassword(encryptedNewPassword);
        if (newPwd == null || newPwd.length() < 6) {
            throw new BusinessException("新密码长度至少6位");
        }
        redis.opsForValue().set(REDIS_KEY, newPwd);
        log.info("管理员密码已更新");
    }

    /** RSA/ECB/PKCS1Padding 解密 (前端 JSEncrypt 默认使用该 padding) */
    public String decryptPassword(String encryptedBase64) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(encryptedBase64);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("RSA 解密失败", e);
            throw new BusinessException("密码解密失败, 请重试");
        }
    }
}
