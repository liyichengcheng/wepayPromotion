package com.wepay.promotion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wepay.promotion.config.WxConfig;
import com.wepay.promotion.util.HttpClientUtil;
import com.wepay.promotion.util.WxPayUtil;
import com.wepay.promotion.util.WxPayV3Util;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
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

    // ======= V3 用户授权免确认模式 接口 =======
    /** 发起免确认收款授权 */
    private static final String V3_APPLY_AUTH_PATH = "/v3/fund-app/mch-transfer/user-confirm-authorization";
    private static final String V3_APPLY_AUTH_URL = V3_HOST + V3_APPLY_AUTH_PATH;
    /** 免确认转账 (用户已授权, 直接到账) */
    private static final String V3_AUTH_TRANSFER_PATH = "/v3/fund-app/mch-transfer/transfer-bills/transfer";
    private static final String V3_AUTH_TRANSFER_URL = V3_HOST + V3_AUTH_TRANSFER_PATH;

    private final WxConfig wxConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** V3 缓存: 商户 API 私钥 */
    private PrivateKey merchantPrivateKey;
    /** V3 缓存: 微信支付公钥 (推荐模式, 用于校验响应签名) */
    private PublicKey wechatpayPublicKey;
    /** V3 缓存: 微信支付公钥 ID (PUB_KEY_ID_xxx, 请求头 Wechatpay-Serial 用) */
    private String wechatpayPublicKeyId;
    /** V3 缓存: 平台证书公钥 (灰度切换期兼容, 已完成切换可不用) */
    private PublicKey platformCertPublicKey;
    /** V3 缓存: 平台证书序列号 (灰度期验签时按响应头 Wechatpay-Serial 匹配) */
    private String platformCertSerial;
    /** V3 缓存: 响应验签密钥集合 (serial -> PublicKey, 公钥ID和平台证书序列号都放进来) */
    private java.util.Map<String, java.security.PublicKey> verifyKeyMap = new java.util.HashMap<>();

    public WxPayService(WxConfig wxConfig) {
        this.wxConfig = wxConfig;
    }

    /** 暴露 WxConfig 供上层 (如 IncomeService) 获取 mchId/appid */
    public WxConfig getWxConfig() {
        return wxConfig;
    }

    @PostConstruct
    public void initV3Keys() {
        try {
            String pkPath = wxConfig.getPay().getPrivateKeyPath();
            if (pkPath != null && !pkPath.isEmpty()) {
                merchantPrivateKey = WxPayV3Util.loadPrivateKey(pkPath);
                log.info("WxPayV3: 商户私钥加载成功 {}", pkPath);
            }
            // 优先加载微信支付公钥 (推荐模式)
            String pubPath = wxConfig.getPay().getPublicKeyPath();
            String pubKeyId = wxConfig.getPay().getPublicKeyId();
            if (pubPath != null && !pubPath.isEmpty() && pubKeyId != null && !pubKeyId.isEmpty()) {
                wechatpayPublicKey = WxPayV3Util.loadPublicKey(pubPath);
                wechatpayPublicKeyId = pubKeyId;
                verifyKeyMap.put(pubKeyId, wechatpayPublicKey);
                log.info("WxPayV3: 微信支付公钥加载成功 id={}, path={}", pubKeyId, pubPath);
            } else {
                log.warn("WxPayV3: 未配置微信支付公钥 (wx.pay.public-key-path / public-key-id), 响应验签将降级");
            }
            // 兼容灰度切换期: 同时加载平台证书 (可选)
            String platPath = wxConfig.getPay().getPlatformCertPath();
            if (platPath != null && !platPath.isEmpty()) {
                try {
                    platformCertPublicKey = WxPayV3Util.loadPlatformPublicKey(platPath);
                    // 从证书文件读取序列号, 用于验签时按响应头 Wechatpay-Serial 匹配
                    platformCertSerial = readCertSerial(platPath);
                    if (platformCertSerial != null) {
                        verifyKeyMap.put(platformCertSerial, platformCertPublicKey);
                        log.info("WxPayV3: 平台证书加载成功 serial={}, path={}", platformCertSerial, platPath);
                    }
                } catch (Exception e) {
                    log.warn("WxPayV3: 平台证书加载失败 (灰度兼容, 可忽略): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("WxPayV3: 初始化密钥失败, 若 mockTransfer=true 则不影响开发, 否则请检查配置", e);
        }
    }

    /** 从 X.509 证书 PEM 读取证书序列号 (大写十六进制) */
    private String readCertSerial(String certPemPath) throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(certPemPath)),
                java.nio.charset.StandardCharsets.UTF_8);
        String b64 = content.replaceAll("-----(BEGIN|END)[^-]+-----", "").replaceAll("\\s", "");
        byte[] der = java.util.Base64.getMimeDecoder().decode(b64);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
        return cert.getSerialNumber().toString(16).toUpperCase();
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
                    wxConfig.getPay().getMerchantSerial(), getWechatpaySerial(), verifyKeyMap, null);
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
                    wxConfig.getPay().getMerchantSerial(), getWechatpaySerial(), verifyKeyMap, null);
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

    /**
     * 发起免确认收款授权 (V3)
     * 接口: POST /v3/fund-app/mch-transfer/user-confirm-authorization
     * 返回 package_info, 前端用 wx.requestMerchantTransfer 拉起微信授权页
     * @param openid              收款用户 openid
     * @param outAuthorizationNo  商户侧授权单号 (商户内唯一, 一个用户对应一个)
     * @return Map: authorization_state, package_info, authorization_id 等
     */
    public Map<String, String> applyTransferAuthorization(String openid, String outAuthorizationNo) throws Exception {
        ensureV3Ready();
        ObjectNode body = WxPayV3Util.newObject();
        body.put("out_authorization_no", outAuthorizationNo);
        body.put("appid", wxConfig.getMiniapp().getAppid());
        body.put("openid", openid);
        body.put("transfer_scene_id", TRANSFER_SCENE_ID);
        body.put("user_display_name", "用户" + openid.substring(Math.max(0, openid.length() - 6)));
        body.put("user_recv_perception", "劳务报酬");
        // 授权回调地址: 必须为 HTTPS (微信 V3 校验)
        String authNotifyUrl = wxConfig.getPay().getNotifyUrl()
                .replace("/pay/notify", "/income/transferAuthNotify")
                .replaceFirst("^http://", "https://");
        body.put("authorization_notify_url", authNotifyUrl);

        String bodyStr = WxPayV3Util.toJson(body);
        log.info("V3 发起免确认授权: openid={}, outAuthNo={}", openid, outAuthorizationNo);

        JsonNode node;
        try {
            Object[] r = WxPayV3Util.executeJson("POST", V3_APPLY_AUTH_URL, V3_APPLY_AUTH_PATH,
                    bodyStr, merchantPrivateKey, wxConfig.getPay().getMchId(),
                    wxConfig.getPay().getMerchantSerial(), getWechatpaySerial(), verifyKeyMap, null);
            node = (JsonNode) r[1];
        } catch (WxPayV3Util.V3ApiException ex) {
            log.error("V3 发起免确认授权失败 status={}, code={}, message={}, raw={}",
                    ex.getStatus(), ex.getCode(), ex.getWpMessage(), ex.getRawBody());
            Map<String, String> fail = new HashMap<>();
            fail.put("return_code", "FAIL");
            fail.put("err_code", ex.getCode() == null ? ("HTTP" + ex.getStatus()) : ex.getCode());
            fail.put("err_code_des", ex.getWpMessage());
            return fail;
        }

        Map<String, String> m = new HashMap<>();
        m.put("return_code", "SUCCESS");
        m.put("authorization_state", textOf(node, "state"));
        m.put("package_info", textOf(node, "package_info"));
        m.put("authorization_id", textOf(node, "authorization_id"));
        m.put("out_authorization_no", outAuthorizationNo);
        log.info("V3 发起免确认授权成功: openid={}, state={}, authorizationId={}",
                openid, m.get("authorization_state"), m.get("authorization_id"));
        return m;
    }

    /**
     * 免确认转账 (V3, 用户已授权, 直接到账零钱)
     * 接口: POST /v3/fund-app/mch-transfer/transfer-bills/transfer
     * 参考文档: https://pay.weixin.qq.com/doc/v3/merchant/4014399371
     * <p>
     * ⚠️ 该接口【不接受 openid 参数】! 因为用户已通过 authorization_id 完成免确认授权,
     * 微信通过 authorization_id / out_authorization_no 即可定位收款方 openid.
     * 若传入 openid, 微信会返回: PARAM_ERROR: 不支持传入openid参数, 请检查
     * <p>
     * ⚠️ authorization_id 与 out_authorization_no 【二选一必填, 不能同时传入】!
     * 若同时传入, 微信会返回: 微信免确认收款授权单号和商户授权单号不支持同时传入
     * <p>
     * 传参优先级:
     * - 优先传 authorization_id (微信生成的, 与授权关系一对一映射, 最权威)
     * - 兜底传 out_authorization_no (商户侧单号, 异步回调未到达时使用)
     * <p>
     * 返回值做了 V2 风格 Map 适配, 让 IncomeService 原逻辑无需改动
     *
     * @param partnerTradeNo       商户转账单号 (out_bill_no)
     * @param openid               收款用户 openid (仅用于日志, 不放入请求体)
     * @param amount               金额(分)
     * @param desc                 转账备注
     * @param authorizationId      微信免确认收款授权单号 (用户确认授权后微信返回, 与 outAuthorizationNo 二选一)
     * @param outAuthorizationNo  商户侧授权单号 (与 authorizationId 二选一, 兜底用)
     */
    public Map<String, String> transferByAuth(String partnerTradeNo, String openid, int amount, String desc,
                                               String authorizationId, String outAuthorizationNo) throws Exception {
        // 二选一必填校验: 至少有一个, 且只传一个到请求体
        boolean hasAuthId = authorizationId != null && !authorizationId.isEmpty();
        boolean hasOutAuthNo = outAuthorizationNo != null && !outAuthorizationNo.isEmpty();
        if (!hasAuthId && !hasOutAuthNo) {
            throw new IllegalArgumentException("authorizationId 和 outAuthorizationNo 至少传一个, 用户未完成免确认授权");
        }

        ObjectNode body = WxPayV3Util.newObject();
        body.put("appid", wxConfig.getMiniapp().getAppid());
        body.put("out_bill_no", partnerTradeNo);
        body.put("transfer_scene_id", TRANSFER_SCENE_ID);
        // ⚠️ 不要传 openid! 免确认转账接口通过 authorization_id / out_authorization_no 定位收款方
        body.put("transfer_amount", amount);
        body.put("transfer_remark", desc == null ? "分享佣金提现" : desc);
        body.put("user_recv_perception", "劳务报酬");
        // ⚠️ authorization_id 和 out_authorization_no 二选一, 不能同时传! 优先 authorization_id
        if (hasAuthId) {
            body.put("authorization_id", authorizationId);
        } else {
            body.put("out_authorization_no", outAuthorizationNo);
        }
        // 转账场景报备信息 (佣金报酬场景必填)
        com.fasterxml.jackson.databind.node.ArrayNode reportInfos = body.putArray("transfer_scene_report_infos");
        ObjectNode info1 = reportInfos.addObject();
        info1.put("info_type", "岗位类型");
        info1.put("info_content", "推广分享员");
        ObjectNode info2 = reportInfos.addObject();
        info2.put("info_type", "报酬说明");
        info2.put("info_content", "文章分享佣金提现");

        String bodyStr = WxPayV3Util.toJson(body);
        log.info("V3 免确认转账入参: out_bill_no={}, openid(仅日志)={}, amount={}, 使用字段={}",
                partnerTradeNo, openid, amount, hasAuthId ? "authorization_id=" + authorizationId : "out_authorization_no=" + outAuthorizationNo);

        // Mock 兜底
        if (Boolean.TRUE.equals(wxConfig.getPay().getMockTransfer())) {
            log.warn("⚠️ mockTransfer=true, 直接模拟 V3 免确认转账成功 (仅开发环境使用!)");
            return mockV3Success(partnerTradeNo);
        }

        ensureV3Ready();

        JsonNode respNode;
        try {
            Object[] r = WxPayV3Util.executeJson("POST", V3_AUTH_TRANSFER_URL, V3_AUTH_TRANSFER_PATH,
                    bodyStr, merchantPrivateKey, wxConfig.getPay().getMchId(),
                    wxConfig.getPay().getMerchantSerial(), getWechatpaySerial(), verifyKeyMap, null);
            respNode = (JsonNode) r[1];
        } catch (WxPayV3Util.V3ApiException ex) {
            log.warn("V3 免确认转账请求失败 status={}, code={}, message={}, raw={}",
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
     * 解除免确认收款授权 (V3)
     * 接口: POST /v3/fund-app/mch-transfer/user-confirm-authorization/out-authorization-no/{out_authorization_no}/close
     * 参考文档: https://pay.weixin.qq.com/doc/v3/merchant/4015653811
     * <p>
     * 解除后授权状态变为 CLOSED, 微信会再次回调 authorization_notify_url (event_type=MCHTRANSFER.AUTHORIZATION.CLOSED)
     *
     * @param outAuthorizationNo 商户侧授权单号 (与申请授权时一致)
     * @return Map: state, close_reason 等
     */
    public Map<String, String> terminateTransferAuthorization(String outAuthorizationNo) throws Exception {
        ensureV3Ready();
        if (outAuthorizationNo == null || outAuthorizationNo.isEmpty()) {
            throw new IllegalArgumentException("outAuthorizationNo 不能为空");
        }

        String path = "/v3/fund-app/mch-transfer/user-confirm-authorization/out-authorization-no/"
                + outAuthorizationNo + "/close";
        String url = V3_HOST + path;
        log.info("V3 解除免确认授权: outAuthorizationNo={}", outAuthorizationNo);

        JsonNode respNode;
        try {
            // 解除授权接口无请求体, 传空字符串
            Object[] r = WxPayV3Util.executeJson("POST", url, path,
                    "", merchantPrivateKey, wxConfig.getPay().getMchId(),
                    wxConfig.getPay().getMerchantSerial(), getWechatpaySerial(), verifyKeyMap, null);
            respNode = (JsonNode) r[1];
        } catch (WxPayV3Util.V3ApiException ex) {
            log.error("V3 解除免确认授权失败 status={}, code={}, message={}, raw={}",
                    ex.getStatus(), ex.getCode(), ex.getWpMessage(), ex.getRawBody());
            Map<String, String> fail = new HashMap<>();
            fail.put("return_code", "FAIL");
            fail.put("err_code", ex.getCode() == null ? ("HTTP" + ex.getStatus()) : ex.getCode());
            fail.put("err_code_des", ex.getWpMessage());
            return fail;
        }

        Map<String, String> m = new HashMap<>();
        m.put("return_code", "SUCCESS");
        m.put("state", textOf(respNode, "state"));
        m.put("close_reason", textOf(respNode, "close_reason"));
        m.put("authorization_id", textOf(respNode, "authorization_id"));
        m.put("out_authorization_no", textOf(respNode, "out_authorization_no"));
        log.info("V3 解除免确认授权成功: outAuthorizationNo={}, state={}", outAuthorizationNo, m.get("state"));
        return m;
    }

    /**
     * 暴露验签公钥 Map + APIv3 密钥, 供回调验签 + 解密使用
     * IncomeService.handleTransferAuthNotify 调用此方法获取公钥做验签
     */
    public Map<String, PublicKey> getVerifyKeyMap() {
        return verifyKeyMap;
    }

    /** 获取 APIv3 密钥 (用于回调 ciphertext AES-GCM 解密) */
    public String getApiV3Key() {
        return wxConfig.getPay().getV3Key();
    }

    private void ensureV3Ready() {
        if (merchantPrivateKey == null) {
            throw new IllegalStateException("V3 商户私钥未加载, 请配置 wx.pay.private-key-path (PKCS8 apiclient_key.pem)");
        }
        if (isEmpty(wxConfig.getPay().getMerchantSerial())) {
            throw new IllegalStateException("wx.pay.merchant-serial 未配置 (商户 API 证书序列号)");
        }
    }

    /**
     * 获取请求头 Wechatpay-Serial 的值: 优先用微信支付公钥ID, 没配置则回退到平台证书序列号
     * 公钥模式: 传 PUB_KEY_ID_xxx, 告诉微信用公钥签名响应
     * 平台证书模式: 传平台证书序列号 (灰度兼容)
     */
    private String getWechatpaySerial() {
        if (StringUtils.isNotBlank(wechatpayPublicKeyId)) {
            return wechatpayPublicKeyId;
        }
        return platformCertSerial;
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
