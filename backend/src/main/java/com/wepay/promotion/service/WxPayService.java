package com.wepay.promotion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wepay.promotion.config.WxConfig;
import com.wepay.promotion.util.HttpClientUtil;
import com.wepay.promotion.util.WxPayUtil;
import com.wepay.promotion.util.WxPayV3Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 微信支付/小程序底层接口封装
 * <p>
 * 提现已切换到 V3 「商家转账到零钱」:
 *   - 发起转账: POST /v3/transfer/batches/single-transfer-by-out-no
 *   - 查询状态: GET  /v3/transfer/batches/by-out-batch-no
 *   - 老 V2 企业付款接口(/mmpaymkttransfers/promotion/transfers)不再适用于新商户
 */
@Slf4j
@Service
public class WxPayService {
    private static final String JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String UNIFIED_ORDER_URL = "https://api.mch.weixin.qq.com/pay/unifiedorder";

    // ======= V3 新版「商家转账」接口 (2025-01-15 升级后) =======
    /** V3 API Host */
    private static final String V3_HOST = "https://api.mch.weixin.qq.com";
    /** 发起商家转账 (用户确认收款模式) */
    private static final String V3_SINGLE_TRANSFER_PATH = "/v3/fund-app/mch-transfer/transfer-bills";
    private static final String V3_SINGLE_TRANSFER_URL = V3_HOST + V3_SINGLE_TRANSFER_PATH;
    /** 查询商家转账单 (by out_bill_no, 路径参数) */
    private static final String V3_QUERY_TRANSFER_PATH_PREFIX = "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/";
    private static final String V3_QUERY_TRANSFER_URL_PREFIX = V3_HOST + V3_QUERY_TRANSFER_PATH_PREFIX;
    /** 佣金返现场景 ID (需在商户平台"商家转账"中申请对应场景) */
    private static final String TRANSFER_SCENE_ID = "1005";

    private final WxConfig wxConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** V3 缓存: 商户 API 私钥 */
    private PrivateKey merchantPrivateKey;
    /** V3 缓存: 微信支付平台公钥 (用于校验响应签名, 未配置则降级) */
    private PublicKey platformPublicKey;

    public WxPayService(WxConfig wxConfig) {
        this.wxConfig = wxConfig;
    }

    @PostConstruct
    public void initV3Keys() {
        try {
            String pkPath = wxConfig.getPay().getPrivateKeyPath();
            if (pkPath != null && !pkPath.isEmpty()) {
                merchantPrivateKey = WxPayV3Util.loadPrivateKey(pkPath);
                log.info("WxPayV3: 商户私钥加载成功 {}", pkPath);
            }
            String platPath = wxConfig.getPay().getPlatformCertPath();
            if (platPath != null && !platPath.isEmpty()) {
                platformPublicKey = WxPayV3Util.loadPlatformPublicKey(platPath);
                log.info("WxPayV3: 平台公钥加载成功 {}", platPath);
            }
        } catch (Exception e) {
            log.error("WxPayV3: 初始化密钥失败, 若 mockTransfer=true 则不影响开发, 否则请检查配置", e);
        }
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
     * 统一下单 (V2 XML, 保持原实现)
     */
    public Map<String, String> unifiedOrder(String outTradeNo, int totalFee, String openid, String body, String ip) throws Exception {
        TreeMap<String, String> params = new TreeMap<>();
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
     * 新版商家转账 (V3, 2025-01-15升级后)
     * <p>
     * 接口: POST /v3/fund-app/mch-transfer/transfer-bills
     * 返回值做了「V2 风格 Map」适配, 让 IncomeService 原逻辑无需改动:
     *   return_code=SUCCESS/FAIL
     *   result_code=SUCCESS/FAIL
     *   payment_no  = 转账单号(微信侧 transfer_bill_no)
     *   payment_time= 到账时间
     *   err_code    = V3 code 或 state
     *   err_code_des= V3 message
     *
     * @param partnerTradeNo 商户转账单号 (out_bill_no)
     * @param openid         收款用户 openid
     * @param amount         金额(分)
     * @param desc           转账备注
     * @param ip             本字段在 V3 中已废弃, 仅保留兼容
     */
    public Map<String, String> transfer(String partnerTradeNo, String openid, int amount, String desc, String ip) throws Exception {
        // 新版商家转账参数: out_bill_no 替代旧版 out_batch_no+out_detail_no
        ObjectNode body = WxPayV3Util.newObject();
        body.put("appid", wxConfig.getMiniapp().getAppid());
        body.put("out_bill_no", partnerTradeNo);
        body.put("transfer_scene_id", TRANSFER_SCENE_ID);
        body.put("openid", openid);
        body.put("transfer_amount", amount);
        body.put("transfer_remark", desc == null ? "分享佣金提现" : desc);
        body.put("user_recv_perception", "劳务报酬");
        // 转账场景报备信息 (佣金报酬场景必填)
        com.fasterxml.jackson.databind.node.ArrayNode reportInfos = body.putArray("transfer_scene_report_infos");
        ObjectNode info1 = reportInfos.addObject();
        info1.put("info_type", "岗位类型");
        info1.put("info_content", "推广分享员");
        ObjectNode info2 = reportInfos.addObject();
        info2.put("info_type", "报酬说明");
        info2.put("info_content", "文章分享佣金提现");

        String bodyStr = WxPayV3Util.toJson(body);
        log.info("V3 商家转账入参: out_bill_no={}, openid={}, amount={}, body={}",
                partnerTradeNo, openid, amount, bodyStr);

        // ======= 开发环境 Mock 兜底 =======
        if (Boolean.TRUE.equals(wxConfig.getPay().getMockTransfer())) {
            log.warn("⚠️ mockTransfer=true, 直接模拟 V3 商家转账成功 (仅开发环境使用!)");
            return mockV3Success(partnerTradeNo);
        }

        ensureV3Ready();

        // ======= 发起 V3 请求 =======
        JsonNode respNode;
        try {
            Object[] r = WxPayV3Util.executeJson("POST", V3_SINGLE_TRANSFER_URL, V3_SINGLE_TRANSFER_PATH,
                    bodyStr, merchantPrivateKey, wxConfig.getPay().getMchId(),
                    wxConfig.getPay().getMerchantSerial(), platformPublicKey, null);
            respNode = (JsonNode) r[1];
        } catch (WxPayV3Util.V3ApiException ex) {
            log.warn("V3 商家转账请求失败 status={}, code={}, message={}, raw={}",
                    ex.getStatus(), ex.getCode(), ex.getWpMessage(), ex.getRawBody());
            Map<String, String> fail = new HashMap<>();
            fail.put("return_code", "SUCCESS");
            fail.put("result_code", "FAIL");
            fail.put("err_code", ex.getCode() == null ? ("HTTP" + ex.getStatus()) : ex.getCode());
            fail.put("err_code_des", ex.getWpMessage() == null ? ("HTTP " + ex.getStatus()) : ex.getWpMessage());
            return fail;
        }

        return adaptV3TransferResultToV2Map(partnerTradeNo, respNode);
    }

    /**
     * 新版 V3 响应适配: state 字段 (ACCEPTED / PROCESSING / WAIT_USER_CONFIRM / TRANSFERING / SUCCESS / FAIL / CANCELING / CANCELLED)
     * 映射到 V2 风格 Map, 让 IncomeService 原逻辑无需改动.
     */
    private Map<String, String> adaptV3TransferResultToV2Map(String partnerTradeNo, JsonNode resp) {
        String state = textOf(resp, "state");
        String transferBillNo = textOf(resp, "transfer_bill_no");

        // SUCCESS: 直接视为成功
        if ("SUCCESS".equalsIgnoreCase(state)) {
            Map<String, String> ok = new HashMap<>();
            ok.put("return_code", "SUCCESS");
            ok.put("result_code", "SUCCESS");
            ok.put("partner_trade_no", partnerTradeNo);
            ok.put("payment_no", transferBillNo == null ? partnerTradeNo : transferBillNo);
            ok.put("payment_time", textOf(resp, "update_time") == null
                    ? java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : toSimpleDateTime(textOf(resp, "update_time")));
            return ok;
        }

        // FAIL / CANCELLED 终态失败
        if (isTerminalFailure(state)) {
            String failReason = firstNonEmpty(textOf(resp, "fail_reason"), "V3 state=" + state);
            Map<String, String> fail = new HashMap<>();
            fail.put("return_code", "SUCCESS");
            fail.put("result_code", "FAIL");
            fail.put("err_code", "V3_" + state);
            fail.put("err_code_des", failReason);
            return fail;
        }

        // 非终态 (ACCEPTED / WAIT_USER_CONFIRM / PROCESSING / TRANSFERING / CANCELING):
        // 返回 FAIL+PROCESSING 让外层阶梯延时查询确认
        log.info("V3 商家转账未到终态 out_bill_no={} state={}, 交给阶梯延时查询确认", partnerTradeNo, state);
        Map<String, String> processing = new HashMap<>();
        processing.put("return_code", "SUCCESS");
        processing.put("result_code", "FAIL");
        processing.put("err_code", "PROCESSING");
        processing.put("err_code_des", "V3 state=" + state);
        return processing;
    }

    private boolean isTerminalFailure(String state) {
        return "FAIL".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state);
    }

    /**
     * 查询商家转账状态 (V3 新版) - 供阶梯延时重试和管理员后台使用
     * 接口: GET /v3/fund-app/mch-transfer/transfer-bills/out-bill-no/{out_bill_no}
     * 返回值做了 V2 风格适配, 上层 IncomeService 无需变动:
     *   transfer_status = SUCCESS / PROCESSING / FAIL
     */
    public Map<String, String> queryTransferStatus(String partnerTradeNo) throws Exception {
        // 路径参数模式: /v3/fund-app/mch-transfer/transfer-bills/out-bill-no/{out_bill_no}
        String encodedBillNo = URLEncoder.encode(partnerTradeNo, StandardCharsets.UTF_8.name());
        String queryPath = V3_QUERY_TRANSFER_PATH_PREFIX + encodedBillNo;
        String fullUrl = V3_QUERY_TRANSFER_URL_PREFIX + encodedBillNo;

        log.debug("V3 查询转账状态 out_bill_no={}", partnerTradeNo);

        if (Boolean.TRUE.equals(wxConfig.getPay().getMockTransfer())) {
            Map<String, String> ok = new HashMap<>();
            ok.put("return_code", "SUCCESS");
            ok.put("result_code", "SUCCESS");
            ok.put("transfer_status", "SUCCESS");
            ok.put("partner_trade_no", partnerTradeNo);
            return ok;
        }

        ensureV3Ready();

        JsonNode node;
        try {
            Object[] r = WxPayV3Util.executeJson("GET", fullUrl, queryPath, "",
                    merchantPrivateKey, wxConfig.getPay().getMchId(),
                    wxConfig.getPay().getMerchantSerial(), platformPublicKey, null);
            node = (JsonNode) r[1];
        } catch (WxPayV3Util.V3ApiException ex) {
            if (ex.getStatus() == 404 || "RESOURCE_NOT_EXISTS".equalsIgnoreCase(ex.getCode())
                    || "NOT_FOUND".equalsIgnoreCase(ex.getCode())) {
                Map<String, String> fail = new HashMap<>();
                fail.put("return_code", "SUCCESS");
                fail.put("result_code", "FAIL");
                fail.put("err_code", "ORDER_NOT_EXIST");
                fail.put("err_code_des", ex.getWpMessage() == null ? "单号不存在" : ex.getWpMessage());
                return fail;
            }
            throw ex;
        }

        // 新版 V3 响应字段: state, transfer_bill_no, fail_reason, update_time
        String state = textOf(node, "state");
        String transferBillNo = textOf(node, "transfer_bill_no");
        String failReason = textOf(node, "fail_reason");

        // 映射到老链路三态
        String transferStatus;
        if ("SUCCESS".equalsIgnoreCase(state)) {
            transferStatus = "SUCCESS";
        } else if (isTerminalFailure(state)) {
            transferStatus = "FAIL";
        } else {
            // ACCEPTED / WAIT_USER_CONFIRM / PROCESSING / TRANSFERING / CANCELING -> PROCESSING
            transferStatus = "PROCESSING";
        }

        Map<String, String> m = new HashMap<>();
        m.put("return_code", "SUCCESS");
        m.put("result_code", "SUCCESS");
        m.put("partner_trade_no", partnerTradeNo);
        m.put("state", state == null ? "" : state);
        m.put("transfer_status", transferStatus);
        if (failReason != null) m.put("fail_reason", failReason);
        m.put("payment_no", transferBillNo == null ? "" : transferBillNo);
        log.debug("V3 查询转账状态 out_bill_no={} state={} -> transfer_status={}",
                partnerTradeNo, state, transferStatus);
        return m;
    }

    // ========== 私有工具方法 ==========

    private void ensureV3Ready() {
        if (merchantPrivateKey == null) {
            throw new IllegalStateException("V3 商户私钥未加载, 请配置 wx.pay.private-key-path (PKCS8 apiclient_key.pem)");
        }
        if (isEmpty(wxConfig.getPay().getMerchantSerial())) {
            throw new IllegalStateException("wx.pay.merchant-serial 未配置 (商户 API 证书序列号)");
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static String textOf(JsonNode n, String field) {
        if (n == null || !n.has(field)) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
    @SafeVarargs
    private static <T> T firstNonEmpty(T... vals) {
        for (T v : vals) {
            if (v != null && !"".equals(v)) return v;
        }
        return null;
    }
    private static String toSimpleDateTime(String iso) {
        // V3 时间格式 "2025-05-20T13:29:35+08:00" -> "2025-05-20 13:29:35"
        if (iso == null) return null;
        try {
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(iso);
            return zdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return iso.replace('T', ' ').replaceAll("\\+\\d{2}:\\d{2}$", "");
        }
    }

    private Map<String, String> mockV3Success(String partnerTradeNo) {
        Map<String, String> m = new HashMap<>();
        m.put("return_code", "SUCCESS");
        m.put("result_code", "SUCCESS");
        m.put("partner_trade_no", partnerTradeNo);
        m.put("payment_no", "V3MOCK" + System.currentTimeMillis());
        m.put("payment_time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        return m;
    }
}
