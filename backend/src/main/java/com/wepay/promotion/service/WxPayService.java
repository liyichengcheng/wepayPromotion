package com.wepay.promotion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wepay.promotion.config.WxConfig;
import com.wepay.promotion.util.HttpClientUtil;
import com.wepay.promotion.util.WxPayUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 微信支付/小程序底层接口封装
 */
@Slf4j
@Service
public class WxPayService {
    private static final String JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String UNIFIED_ORDER_URL = "https://api.mch.weixin.qq.com/pay/unifiedorder";
    private static final String TRANSFER_URL = "https://api.mch.weixin.qq.com/mmpaymkttransfers/promotion/transfers";
    private static final String QUERY_TRANSFER_URL = "https://api.mch.weixin.qq.com/mmpaymkttransfers/gettransferinfo";
    private final WxConfig wxConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WxPayService(WxConfig wxConfig) {
        this.wxConfig = wxConfig;
    }

    /**
     * 小程序 code 换取 openid
     * @return openid, 失败返回 null
     */
    public String jsCode2Session(String code) {
        try {
            String url = JSCODE2SESSION_URL
                    + "?appid=" + wxConfig.getMiniapp().getAppid()
                    + "&secret=" + URLEncoder.encode(wxConfig.getMiniapp().getSecret(), StandardCharsets.UTF_8.name())
                    + "&js_code=" + URLEncoder.encode(code, StandardCharsets.UTF_8.name())
                    + "&grant_type=authorization_code";
            String resp = HttpClientUtil.get(url);
            log.debug("jscode2session 返回: {}", resp);
            JsonNode node = objectMapper.readTree(resp);
            if (node.has("openid")) {
                return node.get("openid").asText();
            }
            log.error("jscode2session 失败: {}", resp);
            return null;
        } catch (Exception e) {
            log.error("jscode2session 异常", e);
            return null;
        }
    }

    /**
     * 统一下单
     * @param outTradeNo 商户订单号
     * @param totalFee   金额(分)
     * @param openid     支付者openid
     * @param body       商品描述
     * @param ip         终端IP
     * @return 微信返回参数(含 prepay_id)
     */
    public Map<String, String> unifiedOrder(String outTradeNo, int totalFee, String openid, String body, String ip) throws Exception {
        SortedMap<String, String> params = new TreeMap<>();
        params.put("appid", wxConfig.getMiniapp().getAppid());
        params.put("mch_id", wxConfig.getPay().getMchId());
        params.put("nonce_str", WxPayUtil.nonceStr());
        params.put("body", body);
        params.put("out_trade_no", outTradeNo);
        params.put("total_fee", String.valueOf(totalFee));
        params.put("spbill_create_ip", ip);
        params.put("notify_url", wxConfig.getPay().getNotifyUrl());
        params.put("trade_type", "JSAPI");
        params.put("openid", openid);
        params.put("sign", WxPayUtil.sign(params, wxConfig.getPay().getMchKey()));

        String xml = WxPayUtil.mapToXml(params);
        String resp = HttpClientUtil.postXml(UNIFIED_ORDER_URL, xml);
        log.debug("统一下单返回: {}", resp);
        return WxPayUtil.xmlToMap(resp);
    }

    /**
     * 企业付款到零钱(佣金转账)
     * @param partnerTradeNo 商户转账单号
     * @param openid         收款用户openid
     * @param amount         金额(分)
     * @param desc           描述
     * @param ip             终端IP
     * @return 微信返回参数
     */
    public Map<String, String> transfer(String partnerTradeNo, String openid, int amount, String desc, String ip) throws Exception {
        SortedMap<String, String> params = new TreeMap<>();
        params.put("mch_appid", wxConfig.getMiniapp().getAppid());
        params.put("mchid", wxConfig.getPay().getMchId());
        params.put("nonce_str", WxPayUtil.nonceStr());
        params.put("partner_trade_no", partnerTradeNo);
        params.put("openid", openid);
        params.put("check_name", "NO_CHECK");
        params.put("amount", String.valueOf(amount));
        params.put("desc", desc);
        params.put("spbill_create_ip", ip);
        params.put("sign", WxPayUtil.sign(params, wxConfig.getPay().getMchKey()));

        // 调试日志: 打印调用参数(除签名)以便排查 NO_AUTH 等配置问题
        if (log.isInfoEnabled()) {
            SortedMap<String, String> debug = new TreeMap<>(params);
            debug.remove("sign");
            log.info("企业付款入参(无签名): url={}, params={}", TRANSFER_URL, debug);
        }

        String xml = WxPayUtil.mapToXml(params);
        String resp;
        try {
            resp = HttpClientUtil.postXmlWithCert(TRANSFER_URL, xml,
                    wxConfig.getPay().getCertPath(), wxConfig.getPay().getMchId());
        } catch (javax.net.ssl.SSLHandshakeException sslEx) {
            // SSL协议错误 (TLSv1 被禁用), 直接抛出明确提示
            log.error("企业付款 SSL 握手失败, 请检查 HttpClientUtil 是否指定了 TLSv1.2+", sslEx);
            throw new RuntimeException("SSL握手失败: " + sslEx.getMessage());
        }

        log.info("企业付款返回: {}", resp);
        Map<String, String> result = WxPayUtil.xmlToMap(resp);

        // NO_AUTH 兜底: 若配置了 mockTransfer=true, 模拟微信返回成功, 便于本地调试
        if ("NO_AUTH".equals(result.get("err_code"))
                && Boolean.TRUE.equals(wxConfig.getPay().getMockTransfer())) {
            log.warn("⚠️ 企业付款返回 NO_AUTH, 命中 mockTransfer=true, 模拟转账成功 (仅开发环境使用!)");
            Map<String, String> mock = new TreeMap<>();
            mock.put("return_code", "SUCCESS");
            mock.put("result_code", "SUCCESS");
            mock.put("partner_trade_no", partnerTradeNo);
            mock.put("payment_no", "MOCK" + System.currentTimeMillis());
            mock.put("payment_time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            return mock;
        }

        return result;
    }

    /**
     * 查询企业付款转账状态(用于阶梯延时重试)
     * @param partnerTradeNo 商户转账单号
     * @return 微信返回参数 (含 transfer_status: SUCCESS/PROCESSING/FAIL)
     */
    public Map<String, String> queryTransferStatus(String partnerTradeNo) throws Exception {
        SortedMap<String, String> params = new TreeMap<>();
        params.put("appid", wxConfig.getMiniapp().getAppid());
        params.put("mch_id", wxConfig.getPay().getMchId());
        params.put("nonce_str", WxPayUtil.nonceStr());
        params.put("partner_trade_no", partnerTradeNo);
        params.put("sign", WxPayUtil.sign(params, wxConfig.getPay().getMchKey()));

        String xml = WxPayUtil.mapToXml(params);
        String resp = HttpClientUtil.postXmlWithCert(QUERY_TRANSFER_URL, xml,
                wxConfig.getPay().getCertPath(), wxConfig.getPay().getMchId());
        log.debug("查询转账状态返回: {}", resp);
        return WxPayUtil.xmlToMap(resp);
    }
}
