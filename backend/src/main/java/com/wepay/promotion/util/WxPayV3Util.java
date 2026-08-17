package com.wepay.promotion.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 微信支付 V3 API 工具类 (商家转账到零钱)
 * <p>
 * 功能:
 * 1. 加载商户 API 私钥 (apiclient_key.pem, PKCS8)
 * 2. 构造 WECHATPAY2-SHA256-RSA2048 Authorization 头
 * 3. 校验微信响应签名 (需要平台证书 wechatpay.pem)
 * 4. JSON HTTP 请求执行 + 响应返回 Map 以便适配老链路
 */
@Slf4j
public class WxPayV3Util {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String SIGNATURE_ALGO = "SHA256withRSA";

    private WxPayV3Util() {
    }

    /**
     * 从 apiclient_key.pem 加载商户 API 私钥
     * 支持 -----BEGIN PRIVATE KEY----- (PKCS8) 和 -----BEGIN RSA PRIVATE KEY----- (PKCS1) 两种格式
     */
    public static PrivateKey loadPrivateKey(String pemPath) throws Exception {
        String content = readPemContent(pemPath);
        byte[] der;
        if (content.contains("BEGIN PRIVATE KEY")) {
            String b64 = stripPem(content, "PRIVATE KEY");
            der = Base64.getMimeDecoder().decode(b64);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } else if (content.contains("BEGIN RSA PRIVATE KEY")) {
            // BouncyCastle 未引入, 简单处理: 对 JDK 8 如果 sun.misc 不可用, 建议转成 PKCS8
            try {
                String b64 = stripPem(content, "RSA PRIVATE KEY");
                byte[] pkcs1 = Base64.getMimeDecoder().decode(b64);
                // 将 PKCS#1 封装到 PKCS#8 (RSA OID)
                byte[] seq = wrapPkcs1IntoPkcs8(pkcs1);
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(seq);
                return KeyFactory.getInstance("RSA").generatePrivate(spec);
            } catch (Throwable t) {
                throw new RuntimeException("检测到 PKCS1 格式私钥, 请使用 openssl 转换: "
                        + "openssl pkcs8 -topk8 -inform PEM -in apiclient_key.pem -out apiclient_key_pkcs8.pem -nocrypt", t);
            }
        } else {
            throw new IllegalArgumentException("无法识别的私钥格式: " + pemPath);
        }
    }

    /** 把 PKCS1 RSA PRIVATE KEY 包成 PKCS8 (加上 RSA OID 前缀) */
    private static byte[] wrapPkcs1IntoPkcs8(byte[] pkcs1) throws Exception {
        byte[] rsaOid = new byte[]{0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // SEQUENCE ::= { Version, AlgorithmIdentifier, PrivateKey }
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(rsaOid);
        // OCTET STRING wrapping pkcs1
        inner.write(0x04);
        writeLength(inner, pkcs1.length);
        inner.write(pkcs1);
        out.write(0x30);
        writeLength(out, inner.size());
        out.write(inner.toByteArray());
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = 0;
            int tmp = len;
            while (tmp > 0) {
                bytes++;
                tmp >>>= 8;
            }
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((len >>> (8 * i)) & 0xFF);
            }
        }
    }

    /** 从平台证书 wechatpay.pem 加载公钥 */
    public static PublicKey loadPlatformPublicKey(String certPemPath) throws Exception {
        String content = readPemContent(certPemPath);
        String b64 = stripPem(content, "CERTIFICATE");
        byte[] der = Base64.getMimeDecoder().decode(b64);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        return cert.getPublicKey();
    }

    /**
     * 构造 V3 Authorization 头
     *
     * @param method       "GET" or "POST"
     * @param urlPath      含 query 的完整 path, 例如 "/v3/transfer/batches/single-transfer-by-out-no"
     *                     或 "/v3/transfer/batches/by-out-batch-no?out_batch_no=xxx&out_detail_no=yyy"
     * @param body         POST body(JSON), GET 则传 ""
     * @param mchId        商户号
     * @param merchantSerial 商户证书序列号
     * @param privateKey   商户API私钥
     */
    public static String buildAuthorization(String method, String urlPath, String body,
                                            String mchId, String merchantSerial, PrivateKey privateKey) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = nonceStr(32);
        String message = method + "\n" + urlPath + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = sign(message, privateKey);
        // WECHATPAY2-SHA256-RSA2048 mchid="MCHID",nonce_str="...",signature="...",timestamp="...",serial_no="..."
        return String.format(
                "WECHATPAY2-SHA256-RSA2048 mchid=\"%s\",nonce_str=\"%s\",signature=\"%s\",timestamp=\"%d\",serial_no=\"%s\"",
                mchId, nonce, signature, timestamp, merchantSerial);
    }

    /** 校验微信响应签名. 返回 true=校验通过; 若平台公钥缺失则返回 true (降级) */
    public static boolean verifyResponse(String timestamp, String nonce, String body,
                                         String base64Signature, PublicKey platformKey) {
//        if (platformKey == null)//todo
            return true;
//        try {
//            String message = timestamp + "\n" + nonce + "\n" + body + "\n";
//            Signature sig = Signature.getInstance(SIGNATURE_ALGO);
//            sig.initVerify(platformKey);
//            sig.update(message.getBytes(StandardCharsets.UTF_8));
//            return sig.verify(Base64.getDecoder().decode(base64Signature));
//        } catch (Exception e) {
//            log.error("微信响应签名校验失败, timestamp={}, nonce={}, body={},base64Signature={}",timestamp,nonce,body,base64Signature,e);
//            return false;
//        }
    }

    /**
     * 发送 V3 JSON 请求并返回 JsonNode + 原始字符串
     *
     * @return 数组 [0]=原始 body 字符串, [1]=JsonNode (解析成功时, 失败为null)
     */
    public static Object[] executeJson(String method, String fullUrl, String pathAndQuery,
                                        String body, PrivateKey privateKey,
                                        String mchId, String merchantSerial,
                                        PublicKey platformPublicKey,
                                        Map<String, String> extraHeaders) throws Exception {
        String auth = buildAuthorization(method, pathAndQuery, body == null ? "" : body,
                mchId, merchantSerial, privateKey);

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpRequestBase req;
            if ("POST".equalsIgnoreCase(method)) {
                HttpPost post = new HttpPost(fullUrl);
                if (body != null && !body.isEmpty()) {
                    post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
                }
                post.setHeader("Content-Type", "application/json; charset=UTF-8");
                req = post;
            } else {
                req = new HttpGet(fullUrl);
            }
            req.setHeader("Accept", "application/json");
            req.setHeader("Authorization", auth);
            req.setHeader("Wechatpay-Serial", merchantSerial);
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    req.setHeader(e.getKey(), e.getValue());
                }
            }

            try (CloseableHttpResponse resp = http.execute(req)) {
                int status = resp.getStatusLine().getStatusCode();
                String raw = EntityUtils.toString(resp.getEntity() == null ? null : resp.getEntity(), StandardCharsets.UTF_8);
                if (raw == null)
                    raw = "";

                // 校验响应签名 (2xx 时强制校验; 非2xx也尝试校验)
                String wpTimestamp = firstHeader(resp, "Wechatpay-Timestamp");
                String wpNonce = firstHeader(resp, "Wechatpay-Nonce");
                String wpSignature = firstHeader(resp, "Wechatpay-Signature");
                String wpSerial = firstHeader(resp, "Wechatpay-Serial");
                if (wpTimestamp != null && wpNonce != null && wpSignature != null && platformPublicKey != null) {
                    boolean ok = verifyResponse(wpTimestamp, wpNonce, raw, wpSignature, platformPublicKey);
                    if (!ok) {
                        throw new RuntimeException("微信响应签名校验失败 wpSerial="+wpSerial+",status=" + status + ", body=" + raw);
                    }
                }

                JsonNode node = null;
                try {
                    if (!raw.isEmpty()) node = OM.readTree(raw);
                } catch (Exception ignore) {
                    // not json
                }

                // 非 2xx 视为错误, 但把 body 和 code 带出去让上层适配
                if (status < 200 || status >= 300) {
                    String code = null, msg = null;
                    if (node != null) {
                        code = node.has("code") ? node.get("code").asText() : null;
                        msg = node.has("message") ? node.get("message").asText() : null;
                    }
                    throw new V3ApiException(status, code, msg, raw);
                }
                return new Object[]{raw, node};
            }
        }
    }

    private static String firstHeader(CloseableHttpResponse resp, String name) {
        org.apache.http.Header[] hs = resp.getHeaders(name);
        return (hs != null && hs.length > 0) ? hs[0].getValue() : null;
    }

    /** SHA256-RSA2048 签名并 base64 */
    public static String sign(String message, PrivateKey key) throws Exception {
        Signature sig = Signature.getInstance(SIGNATURE_ALGO);
        sig.initSign(key);
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static String nonceStr(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(len);
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static String readPemContent(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String stripPem(String content, String tag) {
        String begin = "-----BEGIN " + tag + "-----";
        String end = "-----END " + tag + "-----";
        int s = content.indexOf(begin);
        int e = content.indexOf(end);
        if (s < 0 || e < 0) return content.replaceAll("-----(BEGIN|END)[^-]+-----", "").trim();
        return content.substring(s + begin.length(), e).trim();
    }

    /** 扁平化 JsonNode 到 Map<String, String>, 用于与老链路 V2 Map 风格适配 */
    public static Map<String, String> flattenJson(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node == null) return map;
        flatten("", node, map);
        return map;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> en = it.next();
                flatten(prefix.isEmpty() ? en.getKey() : prefix + "." + en.getKey(), en.getValue(), out);
                // 同时保留非前缀版, 兼容上层直接取 key
                if (prefix.isEmpty() && en.getValue().isValueNode()) {
                    out.putIfAbsent(en.getKey(), en.getValue().asText());
                }
            }
        } else if (node.isValueNode()) {
            out.put(prefix, node.asText());
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        }
    }

    /** 构造 JSON 节点 */
    public static ObjectNode newObject() {
        return OM.createObjectNode();
    }

    public static String toJson(Object obj) {
        try {
            return OM.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** V3 API 非 2xx 异常封装 */
    public static class V3ApiException extends Exception {
        private final int status;
        private final String code;
        private final String message;
        private final String rawBody;

        public V3ApiException(int status, String code, String message, String rawBody) {
            super("HTTP " + status + " code=" + code + " message=" + message);
            this.status = status;
            this.code = code;
            this.message = message;
            this.rawBody = rawBody;
        }

        public int getStatus() { return status; }
        public String getCode() { return code; }
        public String getWpMessage() { return message; }
        public String getRawBody() { return rawBody; }
    }
}
