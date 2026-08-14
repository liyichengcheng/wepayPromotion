package com.wepay.promotion.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES-GCM 加解密工具
 * 密文格式: Base64URL( IV(12B) + ciphertext + authTag(16B) )
 */
public class CryptoUtil {
    private static final String ALGORITHM = "AES";

    /**
     * 加密
     * @param plaintext 明文
     * @param key Base64编码的AES-256密钥 (32字节)
     * @return Base64URL编码的密文 (无padding)
     */
    public static String encrypt(String plaintext, String key) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(key);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes());
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("AES加密失败", e);
        }
    }

    /**
     * 解密
     * @param encrypted Base64URL编码的密文
     * @param key Base64编码的AES-256密钥 (32字节)
     * @return 明文
     */
    public static String decrypt(String encrypted, String key) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(key);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES解密失败", e);
        }
    }
}
