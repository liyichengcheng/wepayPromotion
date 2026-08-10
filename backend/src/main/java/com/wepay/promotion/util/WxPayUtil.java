package com.wepay.promotion.util;

import com.wepay.promotion.dto.PayInfoVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 微信支付 V2 签名 / XML 工具
 */
public class WxPayUtil {

    /**
     * 生成随机字符串 nonce_str
     */
    public static String nonceStr() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 微信支付 V2 MD5 签名
     *
     * @param params 业务参数(会自动按字典序排序, 忽略空值与 sign 字段)
     * @param key    商户密钥 mch_key
     */
    public static String sign(Map<String, String> params, String key) {
        SortedMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null || v.isEmpty() || "sign".equals(k)) {
                continue;
            }
            sb.append(k).append("=").append(v).append("&");
        }
        sb.append("key=").append(key);
        return md5(sb.toString()).toUpperCase();
    }

    public static String md5(String src) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5签名失败", e);
        }
    }

    /**
     * map 转 xml (CDATA 包裹)
     */
    public static String mapToXml(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("<xml>");
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append("<").append(e.getKey()).append(">")
              .append("<![CDATA[").append(e.getValue()).append("]]>")
              .append("</").append(e.getKey()).append(">");
        }
        sb.append("</xml>");
        return sb.toString();
    }

    /**
     * xml 转 map
     */
    public static Map<String, String> xmlToMap(String xml) {
        Map<String, String> map = new TreeMap<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();
            org.w3c.dom.NodeList list = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < list.getLength(); i++) {
                org.w3c.dom.Node node = list.item(i);
                if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    map.put(node.getNodeName(), node.getTextContent());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("XML解析失败: " + e.getMessage(), e);
        }
        return map;
    }

    /**
     * 校验回调签名
     */
    public static boolean verifySign(Map<String, String> params, String key) {
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        return sign.equalsIgnoreCase(sign(params, key));
    }

    /**
     * 构造前端 wx.requestPayment 所需参数并签名
     */
    public static PayInfoVO buildJsapiPayInfo(String appId, String prepayId, String key) {
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = nonceStr();
        String packageX = "prepay_id=" + prepayId;

        SortedMap<String, String> signParams = new TreeMap<>();
        signParams.put("appId", appId);
        signParams.put("timeStamp", timeStamp);
        signParams.put("nonceStr", nonceStr);
        signParams.put("package", packageX);
        signParams.put("signType", "MD5");
        String paySign = sign(signParams, key);

        PayInfoVO vo = new PayInfoVO();
        vo.setTimeStamp(timeStamp);
        vo.setNonceStr(nonceStr);
        vo.setPackageX(packageX);
        vo.setSignType("MD5");
        vo.setPaySign(paySign);
        return vo;
    }
}
