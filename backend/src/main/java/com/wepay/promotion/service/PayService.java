package com.wepay.promotion.service;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.config.WxConfig;
import com.wepay.promotion.dto.CreateOrderRequest;
import com.wepay.promotion.dto.PayInfoVO;
import com.wepay.promotion.entity.PayOrder;
import com.wepay.promotion.entity.User;
import com.wepay.promotion.mapper.PayOrderMapper;
import com.wepay.promotion.mapper.UserMapper;
import com.wepay.promotion.mq.WxPaySuccessMessage;
import com.wepay.promotion.mq.WxPaySuccessProducer;
import com.wepay.promotion.util.WxPayUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PayService {
    public static final int PAY_VALID_DAYS = 30;
    private static final String UNLOCK_KEY = "pay:unlock:%s:%s";
    private static final String ORDER_BODY = "文章阅读-学渣逆袭";
    /** 支付回调以 orderNo 为粒度的分布式锁 key 前缀 */
    private static final String NOTIFY_LOCK_KEY = "pay:notify:lock:%s";
    /** 锁持有时间(秒), 超过此时间自动释放, 防止死锁 */
    private static final long NOTIFY_LOCK_LEASE_SECONDS = 30L;
    /** Lua 脚本: 仅当 value 匹配(锁属于当前持有者)时才删除, 避免误释放他人锁 */
    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else return 0 end";

    private final PayOrderMapper payOrderMapper;
    private final UserMapper userMapper;
    private final WxPayService wxPayService;
    private final StringRedisTemplate redis;
    private final WxConfig wxConfig;
    private final ArticleService articleService;
    private final CommissionService commissionService;
    private final UserService userService;

    public PayService(PayOrderMapper payOrderMapper, UserMapper userMapper,
                      WxPayService wxPayService, StringRedisTemplate redis,
                      WxConfig wxConfig, ArticleService articleService,
                      CommissionService commissionService, UserService userService) {
        this.payOrderMapper = payOrderMapper;
        this.userMapper = userMapper;
        this.wxPayService = wxPayService;
        this.redis = redis;
        this.wxConfig = wxConfig;
        this.articleService = articleService;
        this.commissionService = commissionService;
        this.userService = userService;
    }

    public PayInfoVO createOrder(String openid, CreateOrderRequest req) {
        User user = userMapper.selectByOpenid(openid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        Long articleId = req.getArticleId() == null ? 10001L : req.getArticleId();
        int payPrice = req.getPayPrice() == null ? 600 : req.getPayPrice();

        String orderNo = "po_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);

        PayOrder order = new PayOrder();
        order.setOrderNo(orderNo);
        order.setOpenid(openid);
        order.setArticleId(articleId);
        order.setPayPrice(payPrice);
        String parentShareUid = req.getParentShareUid();
        if (parentShareUid != null && parentShareUid.isEmpty()) {
            parentShareUid = null;
        }
        // 解密分享链接中的加密shareUid, 还原为分享者openid
        if (parentShareUid != null) {
            parentShareUid = userService.decryptShareUid(parentShareUid);
        }
        if (openid.equals(parentShareUid)) {
            parentShareUid = null;
        }
        parentShareUid = "ovQ0yxidmpuPva9wzaZFmFwFAQq4";//todo
        order.setParentShareUid(parentShareUid);
        order.setStatus(0);
        payOrderMapper.insert(order);

        Map<String, String> resp;
        try {
            resp = wxPayService.unifiedOrder(orderNo, payPrice, openid, ORDER_BODY, getServerIp());
        } catch (Exception e) {
            log.error("统一下单失败 orderNo={}", orderNo, e);
            throw new BusinessException("支付服务暂不可用");
        }

        if (!"SUCCESS".equals(resp.get("return_code")) || !"SUCCESS".equals(resp.get("result_code"))) {
            log.error("统一下单失败: {}", resp);
            throw new BusinessException("下单失败: " + resp.get("err_code_des"));
        }

        String prepayId = resp.get("prepay_id");
        return WxPayUtil.buildJsapiPayInfo(wxConfig.getMiniapp().getAppid(), prepayId, wxConfig.getPay().getMchKey());
    }

    public String handleNotify(String xmlRaw) {
        Map<String, String> params;
        try {
            params = WxPayUtil.xmlToMap(xmlRaw);
        } catch (Exception e) {
            log.error("回调XML解析失败", e);
            return notifyFail("XML解析失败");
        }

        if (!WxPayUtil.verifySign(params, wxConfig.getPay().getMchKey())) {
            log.warn("回调签名校验失败: {}", params);
            return notifyFail("签名失败");
        }

        if (!"SUCCESS".equals(params.get("return_code")) || !"SUCCESS".equals(params.get("result_code"))) {
            log.warn("回调非成功状态: {}", params);
            return notifyFail("支付未成功");
        }

        String orderNo = params.get("out_trade_no");
        String transactionId = params.get("transaction_id");
        String openid = params.get("openid");

        // 以 orderNo 为粒度加分布式锁, 防止微信重复回调导致并发处理同一订单
        String lockKey = String.format(NOTIFY_LOCK_KEY, orderNo);
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue()
                .setIfAbsent(lockKey, lockValue, NOTIFY_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            // 已有其它线程正在处理该 orderNo, 直接返回 SUCCESS 让微信停止重试 (幂等)
            log.warn("orderNo={} 正在被其它线程处理, 跳过本次回调", orderNo);
            return notifyFail("前次回调未处理完毕");
        }

        String resp;
        try {
            resp = handleNotify2(orderNo, transactionId, openid);
        } finally {
            // 安全释放锁: 仅当 value 仍为当前持有者时才删除
            try {
                redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
                    byte[] keyBytes = redis.getStringSerializer().serialize(lockKey);
                    byte[] valBytes = redis.getStringSerializer().serialize(lockValue);
                    return connection.eval(UNLOCK_LUA.getBytes(), org.springframework.data.redis.connection.ReturnType.INTEGER,
                            1, keyBytes, valBytes);
                });
            } catch (Exception e) {
                log.warn("释放回调锁失败 orderNo={}, lockKey={}", orderNo, lockKey, e);
            }
        }

        return resp;
    }

    /**
     * 微信支付回调处理
     * 注: 已付费状态仅通过 Redis 缓存(30天TTL)维护
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleNotify2(String orderNo,String transactionId,String openid) {
        // 支付回调只有 order_no 没有分片键 openid:
        // ShardingSphere standard 策略的 SELECT 缺少分片键时自动广播到
        // 4 表 = 16 物理分片, 自动追加后缀: ds{N}.t_pay_order_{M}
        PayOrder order = payOrderMapper.selectByOpenidOrderNo(openid,orderNo);
        if (order == null) {
            log.warn("回调订单不存在: {}", orderNo);
            return notifyFail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() == 1) {
            return notifySuccess();
        }

        // 1. 更新订单为已支付 (t_pay_order.status=1), 携带分片键 openid 实现精准路由
        payOrderMapper.updatePaySuccess(order.getOpenid(), orderNo, transactionId, new Date());
        // 2. 原子递增文章付费计数(Redis)
        articleService.incrementPayTotal(order.getArticleId());
        // 3. 记录已支付状态至 Redis (30天TTL)
        recordPayStatusCache(order, openid);
        // 4. t_pay_order.status=1 后, 发送 RocketMQ 消息异步处理佣金
        try {
            commissionService.handleCommission(order);
        } catch (Exception e) {
            log.error("佣金处理失败，order:{}",order,e);
        }

        return notifySuccess();
    }

    /**
     * 记录已付费状态到 Redis 缓存 (30天TTL)
     */
    private void recordPayStatusCache(PayOrder order, String openid) {
        String key = String.format(UNLOCK_KEY, order.getOpenid(), order.getArticleId());
        redis.opsForValue().set(key, "1", PAY_VALID_DAYS, TimeUnit.DAYS);
    }

    /**
     * 检查用户对某文章的支付状态是否有效(30天内)
     */
    public Map<String, Object> checkPayStatus(String openid, Long articleId) {
        String key = String.format(UNLOCK_KEY, openid, articleId);
        Boolean paid = redis.hasKey(key);
        Map<String, Object> data = new HashMap<>();
        data.put("paid", paid);
        return data;
    }

    private String getServerIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private String notifySuccess() {
        return "<xml><return_code><![CDATA[SUCCESS]]></return_code><return_msg><![CDATA[OK]]></return_msg></xml>";
    }

    private String notifyFail(String msg) {
        return "<xml><return_code><![CDATA[FAIL]]></return_code><return_msg><![CDATA[" + msg + "]]></return_msg></xml>";
    }
}
