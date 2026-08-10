package com.wepay.promotion.util;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

/**
 * HTTP 工具: 用于调用微信支付接口
 */
public class HttpClientUtil {

    /**
     * 普通 HTTPS GET (用于 jscode2session 等接口)
     */
    public static String get(String url) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url);
            try (CloseableHttpResponse resp = client.execute(get)) {
                HttpEntity entity = resp.getEntity();
                return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 普通 HTTPS POST (用于统一下单等无需证书接口)
     */
    public static String postXml(String url, String xml) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setEntity(new StringEntity(xml, StandardCharsets.UTF_8));
            post.setHeader("Content-Type", "text/xml; charset=UTF-8");
            try (CloseableHttpResponse resp = client.execute(post)) {
                HttpEntity entity = resp.getEntity();
                return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 带商户证书的 HTTPS POST (用于企业付款到零钱等需证书接口)
     *
     * @param certPath apiclient_cert.p12 绝对路径
     * @param mchId    商户号(证书密码)
     */
    public static String postXmlWithCert(String url, String xml, String certPath, String mchId) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(certPath)) {
            keyStore.load(is, mchId.toCharArray());
        }
        SSLContext sslContext = SSLContexts.custom()
                .loadKeyMaterial(keyStore, mchId.toCharArray())
                .build();
        SSLConnectionSocketFactory sslFactory = new SSLConnectionSocketFactory(
                sslContext, new String[]{"TLSv1"}, null,
                SSLConnectionSocketFactory.getDefaultHostnameVerifier());
        try (CloseableHttpClient client = HttpClients.custom().setSSLSocketFactory(sslFactory).build()) {
            HttpPost post = new HttpPost(url);
            post.setEntity(new StringEntity(xml, StandardCharsets.UTF_8));
            post.setHeader("Content-Type", "text/xml; charset=UTF-8");
            try (CloseableHttpResponse resp = client.execute(post)) {
                HttpEntity entity = resp.getEntity();
                return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            }
        }
    }
}
