package com.wepay.promotion.service;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.dto.IncomeSummaryVO;
import com.wepay.promotion.entity.CommissionDetail;
import com.wepay.promotion.entity.CommissionSummary;
import com.wepay.promotion.entity.User;
import com.wepay.promotion.entity.Withdraw;
import com.wepay.promotion.mapper.CommissionDetailMapper;
import com.wepay.promotion.mapper.CommissionSummaryMapper;
import com.wepay.promotion.mapper.UserMapper;
import com.wepay.promotion.mapper.WithdrawMapper;
import com.wepay.promotion.util.WxPayV3Util;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IncomeService {
    private static final long ARTICLE_ID = 10001L;
    private static final int BASE_PRICE = 6;
    private static final int MAX_PRICE = 20;
    private static final double COMMISSION_RATE = 0.3;
    private static final double COMMISSION_MIN = 2.0;
    private static final int MIN_WITHDRAW_FEN = 10;//todo，改为100
    private static final int MAX_WITHDRAW_FEN = 100000;

    /** 单笔提现超500元需人工审核 (500元 = 50000分) */
    private static final int SINGLE_WITHDRAW_LIMIT_FEN = 50000;
    /** 当日累计提现达1000元需人工审核 (1000元 = 100000分) */
    private static final long DAILY_WITHDRAW_LIMIT_FEN = 100000L;

    /** 阶梯延时重试间隔(毫秒): 2s -> 5s -> 10s -> 30s -> 60s */
    private static final long[] RETRY_INTERVALS_MS = {2000, 5000, 10000, 30000, 60000};

    /** 提现分布式锁 key 前缀 (openid 粒度), 防并发提交 */
    private static final String WITHDRAW_LOCK_KEY = "withdraw:lock:%s";
    /** 锁租约(秒) */
    private static final long WITHDRAW_LOCK_LEASE_SECONDS = 30L;
    /** 提现限流 key 前缀 (openid 粒度), 每小时只能提现一次 */
    private static final String WITHDRAW_RATE_LIMIT_KEY = "withdraw:ratelimit:%s";
    /** 限流窗口(小时) */
    private static final long WITHDRAW_RATE_LIMIT_HOURS = 1L;
    /** Lua 脚本: 仅当 value 匹配(锁属于当前持有者)时才删除, 避免误释放 */
    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else return 0 end";

    // ======= 免确认收款授权 Redis key =======
    /** 授权状态 key: value = TAKING_EFFECT / WAIT_USER_CONFIRM / none */
    private static final String TRANSFER_AUTH_STATE_KEY = "transfer:auth:state:%s";
    /** 授权详情 key: 存储 outAuthorizationNo + authorizationId */
    private static final String TRANSFER_AUTH_INFO_KEY = "transfer:auth:info:%s";
    /** 授权 package_info 缓存 key: value = package_info (24h 内可幂等返回前端) */
    private static final String TRANSFER_AUTH_PKG_KEY = "transfer:auth:pkg:%s";
    /** 免确认授权申请分布式锁 key (openid 粒度), 防并发调用微信接口 */
    private static final String TRANSFER_AUTH_LOCK_KEY = "transfer:auth:lock:%s";
    /** 授权锁租约(秒), 覆盖一次微信 API 调用耗时 */
    private static final long TRANSFER_AUTH_LOCK_LEASE_SECONDS = 30L;

    private final CommissionSummaryMapper commissionSummaryMapper;
    private final CommissionDetailMapper commissionDetailMapper;
    private final WithdrawMapper withdrawMapper;
    private final UserMapper userMapper;
    private final WxPayService wxPayService;
    private final ArticleService articleService;
    private final StringRedisTemplate redis;

    public IncomeService(CommissionSummaryMapper commissionSummaryMapper,
                        CommissionDetailMapper commissionDetailMapper,
                        WithdrawMapper withdrawMapper,
                        UserMapper userMapper, WxPayService wxPayService,
                        ArticleService articleService, StringRedisTemplate redis) {
        this.commissionSummaryMapper = commissionSummaryMapper;
        this.commissionDetailMapper = commissionDetailMapper;
        this.withdrawMapper = withdrawMapper;
        this.userMapper = userMapper;
        this.wxPayService = wxPayService;
        this.articleService = articleService;
        this.redis = redis;
    }

    public IncomeSummaryVO getUserIncome(String openid) {
        CommissionSummary summary = commissionSummaryMapper.selectByOpenid(openid);
        long totalFen = 0;
        long withdrawableFen = 0;

        if (summary != null) {
            totalFen = summary.getTotalAmount();
            withdrawableFen = Math.max(0,
                    summary.getTotalAmount() - summary.getWithdrawnAmount() - summary.getPendingAmount());
        }
        int todayCount = commissionDetailMapper.countTodayByUser(openid);
        Map<String, Object> payTotalData = articleService.getPayTotal(ARTICLE_ID);
        int totalPayUser = ((Number) payTotalData.get("totalPayUser")).intValue();
        int currentPrice = calcCurrentPrice(totalPayUser);
        double perOrder = currentPrice * COMMISSION_RATE;
        if (perOrder < COMMISSION_MIN) {
            perOrder = COMMISSION_MIN;
        }

        IncomeSummaryVO vo = new IncomeSummaryVO();
        vo.setTotalIncome(fenToYuan(totalFen));
        vo.setTodayIncome(formatYuan(perOrder));
        vo.setWithdrawable(fenToYuan(withdrawableFen));
        vo.setTodayCount(todayCount);
        return vo;
    }

    /**
     * 申请提现
     * 风控规则:
     *   1. 单笔 > 500元 (50000分) -> 状态4=待审核, 需管理员审批
     *   2. 当日累计 + 本次 >= 1000元 (100000分) -> 状态4=待审核
     *   3. 其他情况 -> 直接发起转账
     * 并发控制: 以 openid 为粒度加 Redis 分布式锁, 防并发提交
     * 限流: 同一 openid 每小时只能调用一次
     * @param openid     用户openid
     * @param amountFen  提现金额(分)
     */
    public void applyWithdraw(String openid, Integer amountFen) {
        if (amountFen == null || amountFen < MIN_WITHDRAW_FEN) {
            throw new BusinessException("提现金额至少1元");
        }

        if (amountFen > MAX_WITHDRAW_FEN) {
            throw new BusinessException("单笔提现金额必须小于1000元");
        }

        // 以 openid 为粒度加分布式锁, 防止并发提交提现请求
        String lockKey = String.format(WITHDRAW_LOCK_KEY, openid);
        Boolean locked = redis.opsForValue()
                .setIfAbsent(lockKey, "1", WITHDRAW_RATE_LIMIT_HOURS, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(locked)) {
            throw new BusinessException("每小时只能提现一次, 请稍后再试");
        }

        User user = userMapper.selectByOpenid(openid);
        if (user == null || user.getOpenid() == null) {
            throw new BusinessException("用户不存在或未绑定openid");
        }

        // 1. 获取可提现余额
        CommissionSummary summary = commissionSummaryMapper.selectByOpenid(openid);
        long availableFen = 0;
        if (summary != null) {
            availableFen = Math.max(0,
                    summary.getTotalAmount() - summary.getWithdrawnAmount() - summary.getPendingAmount());
        }
        if (availableFen < amountFen) {
            throw new BusinessException("可提现余额不足");
        }
        // 2. 风控检查: 单笔/当日累计阈值
        long todayTotal = withdrawMapper.sumTodayByUser(openid);
        boolean needReview = amountFen >= SINGLE_WITHDRAW_LIMIT_FEN
                || (todayTotal + amountFen) >= DAILY_WITHDRAW_LIMIT_FEN;

        applyWithdrawInTransaction(needReview, openid, amountFen, todayTotal);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyWithdrawInTransaction(boolean needReview, String openid, long amountFen, long todayTotal) {
        if (needReview) {
            // 需审核: 创建提现单, 状态=4(待审核), 不调微信接口
            addWithdraw(openid, amountFen, 4);

            log.warn("提现需人工审核: openid={}, 金额={}分, 当日累计={}分, 原因={}", openid, amountFen, todayTotal,
                    amountFen >= SINGLE_WITHDRAW_LIMIT_FEN ? "单笔超500元" : "当日累计超1000元");
            throw new BusinessException("提现金额较大, 已提交人工审核, 请等待管理员审批");
        }
        Withdraw withdraw = addWithdraw(openid, amountFen, 1);
        executeTransfer(openid, withdraw, (int) amountFen);
    }

    private Withdraw addWithdraw(String openid,long availableFen,Integer status) {
        // 3. 正常提现: 直接发起转账
        Withdraw withdraw = new Withdraw();
        withdraw.setOpenid(openid);
        withdraw.setAmount((int) availableFen);
        withdraw.setStatus(status);
        // 4. 调用微信企业付款接口 (partner_trade_no 只能是字母或数字, 不能含下划线)
        String transferNo = "wd" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
        withdraw.setTransferNo(transferNo);
        withdrawMapper.insert(withdraw);
        // 冻结佣金: 增加pendingAmount
        commissionSummaryMapper.incrementPendingAmount(openid, (int) availableFen);
        return withdraw;
    }

    /**
     * 执行微信转账 (可被用户提现和管理员审核通过后调用)
     * 含阶梯延时重试: 调接口无响应时, 根据transferNo轮询查询状态
     * 已授权免确认用户走 transferByAuth (直接到账), 未授权走 transfer (用户确认模式)
     */
    @Transactional(rollbackFor = Exception.class)
    public void executeTransfer(String openid, Withdraw withdraw, int amountFen) {
        String ip = getServerIp();
        boolean apiCalled = false;

        Long withdrawId = withdraw.getId();
        String transferNo = withdraw.getTransferNo();
        // 检查免确认授权状态: 已授权走免确认转账 (需带 authorization_id/out_authorization_no), 未授权走用户确认模式
        boolean authFree = isTransferAuthEffective(openid);
        try {
            Map<String, String> resp;
            if (authFree) {
                String authorizationId = getAuthorizationId(openid);
                String outAuthNo = getOutAuthorizationNo(openid);
                if (authorizationId == null && outAuthNo == null) {
                    // 授权状态异常: 状态为 TAKING_EFFECT 但 authorizationId/outAuthNo 都丢失, 降级走用户确认模式
                    log.warn("授权状态为 TAKING_EFFECT 但 authorizationId 和 outAuthorizationNo 均丢失, 降级走用户确认模式: openid={}", openid);
                    resp = wxPayService.transfer(transferNo, openid, amountFen, "分享佣金提现", ip);
                } else {
                    log.info("用户已授权免确认, 走免确认转账: openid={}, transferNo={}, authorizationId={}, outAuthorizationNo={}",
                            openid, transferNo, authorizationId, outAuthNo);
                    resp = wxPayService.transferByAuth(transferNo, openid, amountFen, "分享佣金提现", authorizationId, outAuthNo);
                }
            } else {
                resp = wxPayService.transfer(transferNo, openid, amountFen, "分享佣金提现", ip);
            }
            apiCalled = true;

            if ("SUCCESS".equals(resp.get("return_code")) && "SUCCESS".equals(resp.get("result_code"))) {
                handleTransferSuccess(openid, withdrawId, transferNo, amountFen);
                log.info("提现成功: openid={}, 金额={}分, transferNo={}, paymentNo={}",
                        openid, amountFen, transferNo, resp.get("payment_no"));
            } else {
                String failReason = resp.get("err_code") + ":" + resp.get("err_code_des");
                handleTransferFailed(openid, withdrawId, transferNo, amountFen);
                throw new BusinessException("提现失败: " + failReason);
            }
        } catch (Exception e) {
            log.error("",e);
            if (apiCalled) {
                // 接口有响应但处理异常
                log.error("提现处理异常: openid={}, transferNo={}", openid, transferNo, e);
                handleTransferFailed(openid, withdrawId, transferNo, amountFen);
                throw new BusinessException("提现处理异常, 请稍后重试");
            } else {
                // 接口无响应: 阶梯延时查询
                log.warn("转账接口无响应, 开始阶梯延时查询: openid={}, transferNo={}", openid, transferNo, e);
                queryAndHandlerTransferWithBackoff(withdrawId,openid, transferNo, amountFen);
            }
        }
    }

    public void queryAndHandlerTransferWithBackoff(Long withdrawId,String openid, String transferNo,int amountFen)
            throws BusinessException {
        String queryResult = queryTransferWithBackoff(transferNo);
        if ("SUCCESS".equals(queryResult)) {
            handleTransferSuccess(openid, withdrawId, transferNo, amountFen);
            log.info("延时查询确认转账成功: openid={}, transferNo={}", openid, transferNo);
        } else if ("FAIL".equals(queryResult)) {
            handleTransferFailed(openid, withdrawId, transferNo, amountFen);
            throw new BusinessException("提现失败, 微信返回: FAIL");
        } else {
            // 仍不确定: 标记为待人工处理
            log.error("阶梯延时查询仍无法确认转账状态: openid={}, transferNo={}", openid, transferNo);
            withdrawMapper.updateTransferNo(openid, withdrawId, transferNo, 1); // 保持处理中
            throw new BusinessException("转账接口无响应且查询超时, 已标记处理中, 请稍后或联系管理员");
        }
    }

    /**
     * 阶梯延时查询转账状态
     * 延时策略: 2s -> 5s -> 10s -> 30s -> 60s, 共5次查询
     * @return "SUCCESS" / "FAIL" / "UNKNOWN"
     */
    private String queryTransferWithBackoff(String transferNo) {
        for (int i = 0; i < RETRY_INTERVALS_MS.length; i++) {
            try {
                Thread.sleep(RETRY_INTERVALS_MS[i]);
                Map<String, String> resp = wxPayService.queryTransferStatus(transferNo);
                log.info("第{}次查询转账状态 transferNo={}: {}", i + 1, transferNo, resp);

                if ("SUCCESS".equals(resp.get("return_code"))
                        && "SUCCESS".equals(resp.get("result_code"))) {
                    String status = resp.get("transfer_status");
                    if ("SUCCESS".equals(status)) {
                        return "SUCCESS";
                    } else if ("FAIL".equals(status)) {
                        return "FAIL";
                    }
                }
            } catch (Exception e) {
                log.warn("第{}次查询转账状态异常 transferNo={}", i + 1, transferNo, e);
            }
        }
        return "UNKNOWN";
    }

    public void handleTransferSuccess(String openid, Long withdrawId, String transferNo, int amountFen) {
        withdrawMapper.updateTransferNo(openid, withdrawId, transferNo, 2);
        commissionSummaryMapper.markWithdrawSuccess(openid, amountFen);
    }

    public void handleTransferFailed(String openid, Long withdrawId, String transferNo, int amountFen) {
        withdrawMapper.updateTransferNo(openid, withdrawId, transferNo, 3);
        commissionSummaryMapper.markWithdrawFailed(openid, amountFen);
    }

    /**
     * 管理员审核通过后执行提现
     */
    @Transactional(rollbackFor = Exception.class)
    public void adminApproveAndWithdraw(String openid, Long withdrawId) {
        Withdraw withdraw = withdrawMapper.selectById(openid, withdrawId);
        if (withdraw == null) {
            throw new BusinessException("提现单不存在");
        }
        if (withdraw.getStatus() != 4) {
            throw new BusinessException("该提现单不是待审核状态");
        }

        // 更新状态为处理中
        withdrawMapper.updateStatus(openid, withdrawId, 1);
        executeTransfer(openid, withdraw, withdraw.getAmount());
    }

    /**
     * 管理员拒绝提现
     */
    @Transactional(rollbackFor = Exception.class)
    public void adminRejectWithdraw(String openid, Long withdrawId) {
        Withdraw withdraw = withdrawMapper.selectById(openid, withdrawId);
        if (withdraw == null) {
            throw new BusinessException("提现单不存在");
        }
        if (withdraw.getStatus() != 4) {
            throw new BusinessException("该提现单不是待审核状态");
        }

        // 状态改为失败(拒绝)
        withdrawMapper.updateStatus(openid, withdrawId, 3);
        commissionSummaryMapper.markWithdrawFailed(openid, withdraw.getAmount());
        log.info("管理员拒绝提现: openid={}, withdrawId={}", openid, withdrawId);
    }

    /**
     * 重新发起提现 (针对status=1处理中但结果不明的提现单)
     * 1. 若已有transferNo, 先查微信状态; SUCCESS直接成功, FAIL/UNKNOWN再重新调用转账
     * 2. 若没有transferNo, 直接发起转账
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reInitiateWithdraw(String openid, Long withdrawId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        Withdraw withdraw = withdrawMapper.selectById(openid, withdrawId);
        if (withdraw == null) {
            throw new BusinessException("提现单不存在");
        }
        if (withdraw.getStatus() != 1) {
            throw new BusinessException("仅支持对处理中(status=1)的提现单重新发起");
        }

        String existingTransferNo = withdraw.getTransferNo();
        int amount = withdraw.getAmount();

        // 步骤1: 如果已有transferNo, 先查微信状态
        if (StringUtils.isNotBlank(existingTransferNo)) {
            try {
                Map<String, String> queryResp = wxPayService.queryTransferStatus(existingTransferNo);
                result.put("preQuery", queryResp);
                if ("SUCCESS".equals(queryResp.get("return_code"))
                        && "SUCCESS".equals(queryResp.get("result_code"))) {
                    String transferStatus = queryResp.get("transfer_status");
                    if ("SUCCESS".equals(transferStatus)) {
                        handleTransferSuccess(openid, withdrawId, existingTransferNo, amount);
                        result.put("action", "previous_transfer_confirmed_success");
                        return result;
                    } else if ("PROCESSING".equals(transferStatus)) {
                        result.put("action", "previous_transfer_still_processing_skipping");
                        return result;
                    }
                    // FAIL or NOTFOUND -> continue to re-initiate
                    result.put("preQueryNote", "previous status: " + transferStatus + ", will re-initiate with new transferNo");
                }
            } catch (Exception e) {
                log.warn("reInitiateWithdraw 预查询状态失败: transferNo={}", existingTransferNo, e);
                result.put("preQueryError", e.getMessage());
            }
        }

        // 步骤2: 生成新transferNo, 重新调用转账接口
        try {
            executeTransfer(openid, withdraw, amount);
            result.put("action", "re_initiate_success");
        } catch (BusinessException e) {
            result.put("action", "re_initiate_failed");
            result.put("error", e.getMessage());
            throw e;
        } catch (Exception e) {
            result.put("action", "re_initiate_exception");
            result.put("error", e.getMessage());
            throw new BusinessException("重新发起提现异常: " + e.getMessage());
        }
        return result;
    }

    private int calcCurrentPrice(int totalPayUser) {
        if (totalPayUser <= 1000) {
            return BASE_PRICE;
        }
        int add = (totalPayUser - 1000) / 10000;
        int price = BASE_PRICE + add;
        return Math.min(price, MAX_PRICE);
    }

    private String fenToYuan(long fen) {
        return BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).toString();
    }

    private String formatYuan(double yuan) {
        return BigDecimal.valueOf(yuan).setScale(2, RoundingMode.HALF_UP).toString();
    }

    private String getServerIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 安全释放分布式锁: 仅当 value 匹配(锁属于当前持有者)时才删除, 避免误释放他人锁
     */
    private void releaseLock(String lockKey, String lockValue) {
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
                byte[] keyBytes = redis.getStringSerializer().serialize(lockKey);
                byte[] valBytes = redis.getStringSerializer().serialize(lockValue);
                return connection.eval(UNLOCK_LUA.getBytes(),
                        org.springframework.data.redis.connection.ReturnType.INTEGER, 1, keyBytes, valBytes);
            });
        } catch (Exception e) {
            log.warn("释放分布式锁失败 lockKey={}", lockKey, e);
        }
    }

    /**
     * 构造当前授权状态的返回结果 (并发锁竞争失败时使用)
     */
    private Map<String, Object> buildCurrentStateResult(String currentState, String openid) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        String state = StringUtils.isNotBlank(currentState) ? currentState : "NONE";
        m.put("state", state);
        if ("WAIT_USER_CONFIRM".equals(state)) {
            m.put("message", "授权申请处理中, 请稍后再试");
        } else if ("TAKING_EFFECT".equals(state)) {
            m.put("message", "已授权免确认收款");
        } else {
            m.put("message", "授权申请处理中, 请稍后再试");
        }
        return m;
    }

    // ==================== 免确认收款授权 ====================
    /**
     * 申请免确认收款授权 (供小程序端调 wx.requestMerchantTransfer 拉起微信授权页)
     * <p>
     * 流程:
     * 1. 首次申请: 调微信接口, 返回 state=WAIT_USER_CONFIRM + package_info
     * 2. 小程序用 package_info 调 wx.requestMerchantTransfer 拉起微信授权页
     * 3. 用户在微信内点击确认授权
     * 4. 微信异步回调 transferAuthNotify, state 变为 TAKING_EFFECT
     * 5. 之后调用 applyWithdraw 走免确认转账 (transferByAuth)
     * <p>
     * 幂等优化:
     * - TAKING_EFFECT: 直接返回, 不再申请
     * - WAIT_USER_CONFIRM 且 package_info 未过期: 直接返回缓存的 package_info, 不再调微信
     * @return Map: state, package_info, mchId, appId (前端拉起授权页需要)
     */
    public Map<String, Object> applyTransferAuth(String openid) {
        // 以 openid 为粒度加分布式锁, 防止并发调用微信接口
        String authLockKey = String.format(TRANSFER_AUTH_LOCK_KEY, openid);
        String authLockValue = UUID.randomUUID().toString();

        try {
            Boolean authLocked = redis.opsForValue()
                    .setIfAbsent(authLockKey, authLockValue, TRANSFER_AUTH_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(authLocked)) {
                log.info("用户[{}]授权申请正在处理中, 返回当前状态", openid);
                return buildCurrentStateResult(null, openid);
            }

            String stateKey = String.format(TRANSFER_AUTH_STATE_KEY, openid);
            String infoKey = String.format(TRANSFER_AUTH_INFO_KEY, openid);
            String pkgKey = String.format(TRANSFER_AUTH_PKG_KEY, openid);

            // 1. 已授权生效 (TAKING_EFFECT): 无需再申请
            String currentState = redis.opsForValue().get(stateKey);
            if ("TAKING_EFFECT".equals(currentState)) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("state", "TAKING_EFFECT");
                m.put("message", "已授权免确认收款, 无需重复申请");
                return m;
            }

            // 2. WAIT_USER_CONFIRM 且 package_info 仍在 24h 有效期内: 直接返回缓存
            if ("WAIT_USER_CONFIRM".equals(currentState)) {
                String cachedPkg = redis.opsForValue().get(pkgKey);
                String cachedInfo = redis.opsForValue().get(infoKey);
                if (StringUtils.isNotBlank(cachedPkg) && cachedInfo != null) {
                    String[] parts = cachedInfo.split("\\|");
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("state", "WAIT_USER_CONFIRM");
                    m.put("package_info", cachedPkg);
                    m.put("mchId", wxPayService.getWxConfig().getPay().getMchId());
                    m.put("appId", wxPayService.getWxConfig().getMiniapp().getAppid());
                    m.put("outAuthorizationNo", parts[0]);
                    m.put("message", "请用 package_info 调 wx.requestMerchantTransfer 拉起微信授权页, 用户确认后状态会变为 TAKING_EFFECT");
                    return m;
                }
                // package_info 已过期, 走重新申请流程 (复用 outAuthorizationNo)
            }

           // 3. 首次申请或 package_info 过期: 调微信接口
            String existingInfo = redis.opsForValue().get(infoKey);
            String outAuthorizationNo;
            if (StringUtils.isNotBlank(existingInfo)) {
                // 复用已有 outAuthorizationNo (24h 内)
                String[] parts = existingInfo.split("\\|");
                outAuthorizationNo = parts[0];
            } else {
                outAuthorizationNo = "auth" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
            }

            try {
                Map<String, String> resp = wxPayService.applyTransferAuthorization(openid, outAuthorizationNo);
                if (!"SUCCESS".equals(resp.get("return_code"))) {
                    throw new BusinessException("申请免确认授权失败: " + resp.get("err_code_des"));
                }
                String state = resp.get("authorization_state");
                String packageInfo = resp.get("package_info");
                String authorizationId = resp.get("authorization_id");

                // DB 持久化 outAuthorizationNo (确保 t_user 行存在并更新授权单号)
                try {
                    User user = userMapper.selectByOpenid(openid);
                    if (user == null) {
                        User newUser = new User();
                        newUser.setOpenid(openid);
                        newUser.setOutAuthorizationNo(outAuthorizationNo);
                        userMapper.insert(newUser);
                    } else {
                        userMapper.updateAuthorizationNo(openid, outAuthorizationNo);
                    }
                } catch (Exception dbEx) {
                    log.warn("DB 更新 outAuthorizationNo 失败 (Redis 已缓存, 不影响流程): openid={}", openid, dbEx);
                }

                // 缓存 outAuthorizationNo + authorizationId (24h 有效, 与微信授权申请有效期一致)
                redis.opsForValue().set(infoKey, outAuthorizationNo + "|" + (authorizationId == null ? "" : authorizationId),
                        24, TimeUnit.HOURS);
                // 缓存 package_info (24h 有效, 与授权申请有效期一致; 用于幂等返回)
                if (StringUtils.isNotBlank(packageInfo)) {
                    redis.opsForValue().set(pkgKey, packageInfo, 24, TimeUnit.HOURS);
                }
                // 更新状态
                redis.opsForValue().set(stateKey, state == null ? "WAIT_USER_CONFIRM" : state, 30, TimeUnit.DAYS);

                Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("state", state);
                result.put("package_info", packageInfo);
                result.put("mchId", wxPayService.getWxConfig().getPay().getMchId());
                result.put("appId", wxPayService.getWxConfig().getMiniapp().getAppid());
                result.put("outAuthorizationNo", outAuthorizationNo);
                return result;
            } catch (Exception e) {
                log.error("申请免确认收款授权异常: openid={}", openid, e);
                throw new BusinessException("申请免确认授权异常: " + e.getMessage());
            }
        } finally {
            releaseLock(authLockKey, authLockValue);
        }
    }

    /**
     * 处理微信授权结果异步通知
     * 完整流程 (参考 https://pay.weixin.qq.com/doc/v3/merchant/4014512908):
     * 1. 验签: 用微信支付公钥/平台证书公钥校验 Wechatpay-Signature
     * 2. 解密: resource.ciphertext 是 AES-GCM-256 加密的, 用 APIv3 密钥解密
     * 3. 解析明文: authorization_state, openid, authorization_id, out_authorization_no, close_reason
     * 4. 更新 DB + Redis 状态
     * @param jsonBody            HTTP 请求 body 原文 (回调报文 JSON)
     * @param wechatpayTimestamp  HTTP 头 Wechatpay-Timestamp
     * @param wechatpayNonce      HTTP 头 Wechatpay-Nonce
     * @param wechatpaySerial     HTTP 头 Wechatpay-Serial (公钥ID或平台证书序列号)
     * @param wechatpaySignature  HTTP 头 Wechatpay-Signature
     */
    public String handleTransferAuthNotify(String jsonBody, String wechatpayTimestamp, String wechatpayNonce,
                                            String wechatpaySerial, String wechatpaySignature) {
        try {
            // 1. 验签 (强制要求, 防止伪造回调)
            java.util.Map<String, java.security.PublicKey> verifyKeyMap = wxPayService.getVerifyKeyMap();
            if (verifyKeyMap == null || verifyKeyMap.isEmpty()) {
                log.error("回调验签失败: 验签公钥未加载 (请配置 wx.pay.public-key-path 或 platform-cert-path)");
                return "{\"code\":\"FAIL\",\"message\":\"验签公钥未配置\"}";
            }
            if (wechatpayTimestamp == null || wechatpayNonce == null || wechatpaySignature == null) {
                log.error("回调验签失败: 签名头缺失");
                return "{\"code\":\"FAIL\",\"message\":\"签名头缺失\"}";
            }
            java.security.PublicKey verifyKey = wechatpaySerial != null ? verifyKeyMap.get(wechatpaySerial) : null;
            if (verifyKey == null) {
                // 兜底: 若只有一个验签密钥, 用之
                if (verifyKeyMap.size() == 1) {
                    verifyKey = verifyKeyMap.values().iterator().next();
                } else {
                    log.error("回调验签失败: Wechatpay-Serial={} 不匹配任何已加载公钥", wechatpaySerial);
                    return "{\"code\":\"FAIL\",\"message\":\"serial不匹配\"}";
                }
            }
            boolean signOk = WxPayV3Util.verifyNotifySignature(verifyKey, wechatpayTimestamp,
                    wechatpayNonce, jsonBody, wechatpaySignature);
             if (!signOk) {
                log.error("回调验签失败: openid 序列号={}", wechatpaySerial);
                return "{\"code\":\"FAIL\",\"message\":\"验签失败\"}";
            }

            // 2. 解析外层报文, 拿到 resource 密文
            com.fasterxml.jackson.databind.JsonNode outer = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonBody);
            String eventType = outer.has("event_type") ? outer.get("event_type").asText() : null;
            com.fasterxml.jackson.databind.JsonNode resource = outer.has("resource") ? outer.get("resource") : null;
            if (resource == null) {
                log.error("回调 resource 缺失: {}", jsonBody);
                return "{\"code\":\"FAIL\",\"message\":\"resource缺失\"}";
            }
            String ciphertext = resource.has("ciphertext") ? resource.get("ciphertext").asText() : null;
            String nonce = resource.has("nonce") ? resource.get("nonce").asText() : null;
            String associatedData = resource.has("associated_data") ? resource.get("associated_data").asText() : null;
            if (ciphertext == null || nonce == null) {
                log.error("回调 ciphertext/nonce 缺失");
                return "{\"code\":\"FAIL\",\"message\":\"密文缺失\"}";
            }

            // 3. AES-GCM-256 解密
            String apiV3Key = wxPayService.getApiV3Key();
            if (apiV3Key == null || apiV3Key.length() != 32) {
                log.error("回调解密失败: APIv3 密钥未配置或长度非 32 字节");
                return "{\"code\":\"FAIL\",\"message\":\"APIv3密钥未配置\"}";
            }
            String plainJson = WxPayV3Util.decryptAesGcm(apiV3Key, associatedData, nonce, ciphertext);
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(plainJson);

            String state = node.has("state") ? node.get("state").asText() : null;
            String openid = node.has("openid") ? node.get("openid").asText() : null;
            String authorizationId = node.has("authorization_id") ? node.get("authorization_id").asText() : null;
            String outAuthNo = node.has("out_authorization_no") ? node.get("out_authorization_no").asText() : null;

            log.info("收到免确认授权回调 (已验签+解密): event_type={}, openid={}, state={}, authorizationId={}, outAuthNo={}",
                    eventType, openid, state, authorizationId, outAuthNo);

            if (openid == null) {
                return "{\"code\":\"FAIL\",\"message\":\"openid缺失\"}";
            }
            // TAKING_EFFECT 必须有 authorization_id, 否则后续 transferByAuth 会失败
            if ("TAKING_EFFECT".equals(state) && StringUtils.isBlank(authorizationId)) {
                log.error("授权回调 state=TAKING_EFFECT 但 authorization_id 缺失, openid={}, outAuthNo={}", openid, outAuthNo);
                return "{\"code\":\"FAIL\",\"message\":\"authorization_id缺失\"}";
            }

            // 更新授权状态 (TAKING_EFFECT / WAIT_USER_CONFIRM / EXPIRED / CLOSED)
            redis.opsForValue().set(String.format(TRANSFER_AUTH_STATE_KEY, openid),
                    state == null ? "" : state, 30, TimeUnit.DAYS);

            // redis,DB双写
            try {
                if ("TAKING_EFFECT".equals(state) && StringUtils.isNotBlank(authorizationId)) {
                    redis.opsForValue().set(String.format(TRANSFER_AUTH_INFO_KEY, openid),
                            outAuthNo + "|" + authorizationId, 30, TimeUnit.DAYS);
                    userMapper.updateAuthorizationId(openid, authorizationId);
                } else if ("CLOSED".equals(state) || "EXPIRED".equals(state)
                        || "REVOKED".equals(state) || "CANCELED".equals(state)) {
                    redis.delete(String.format(TRANSFER_AUTH_INFO_KEY, openid));
                    userMapper.clearAuthorization(openid);
                }
            } catch (Exception dbEx) {
                log.warn("DB,Redis更新授权状态失败: openid={}", openid, dbEx);
            }

            // 授权终态清除 package_info 缓存 (TAKING_EFFECT 时无需再拉起授权页)
            if ("TAKING_EFFECT".equals(state) || "CLOSED".equals(state)
                    || "EXPIRED".equals(state) || "REVOKED".equals(state) || "CANCELED".equals(state)) {
                redis.delete(String.format(TRANSFER_AUTH_PKG_KEY, openid));
            }
            return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
        } catch (Exception e) {
            log.error("处理免确认授权回调异常", e);
            return "{\"code\":\"FAIL\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询当前用户免确认授权状态 (供前端展示)
     */
    public Map<String, Object> getTransferAuthStatus(String openid) {
        String state = redis.opsForValue().get(String.format(TRANSFER_AUTH_STATE_KEY, openid));
        String info = redis.opsForValue().get(String.format(TRANSFER_AUTH_INFO_KEY, openid));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("state", state == null ? "NONE" : state);
        m.put("effective", "TAKING_EFFECT".equals(state));
        if (info != null) {
            String[] parts = info.split("\\|");
            m.put("outAuthorizationNo", parts[0]);
            if (parts.length > 1)
                m.put("authorizationId", parts[1]);
        }
        return m;
    }

    /** 检查用户是否已授权免确认收款 (TAKING_EFFECT 状态) */
    private boolean isTransferAuthEffective(String openid) {
        String state = redis.opsForValue().get(String.format(TRANSFER_AUTH_STATE_KEY, openid));
        return "TAKING_EFFECT".equals(state);
    }

    /**
     * 从 Redis 获取用户的微信免确认收款授权单号 (authorization_id)
     * 用户在微信内确认授权后, applyTransferAuthorization 同步返回 + transferAuthNotify 异步回调都会写入此值
     * Redis 未命中时降级查 DB (t_user.authorization_id)
     * @return authorizationId; 若未授权或丢失返回 null
     */
    private String getAuthorizationId(String openid) {
        // 1. 优先从 Redis 取
        String info = redis.opsForValue().get(String.format(TRANSFER_AUTH_INFO_KEY, openid));
        if (StringUtils.isNotBlank(info)) {
            String[] parts = info.split("\\|");
            if (parts.length >= 2 && StringUtils.isNotBlank(parts[1])) {
                return parts[1];
            }
        }
        // 2. 降级从 DB 取
        try {
            User user = userMapper.selectByOpenid(openid);
            if (user != null && StringUtils.isNotBlank(user.getAuthorizationId())) {
                // 回填 Redis, 加速后续读取
                String outAuthNo = user.getOutAuthorizationNo() == null ? "" : user.getOutAuthorizationNo();
                redis.opsForValue().set(String.format(TRANSFER_AUTH_INFO_KEY, openid),
                        outAuthNo + "|" + user.getAuthorizationId(), 30, TimeUnit.DAYS);
                return user.getAuthorizationId();
            }
        } catch (Exception e) {
            log.warn("从 DB 获取 authorizationId 失败: openid={}", openid, e);
        }
        return null;
    }

    /**
     * 从 Redis 获取商户侧授权单号 (out_authorization_no)
     * Redis 未命中时降级查 DB (t_user.out_authorization_no)
     * @return outAuthorizationNo; 若无返回 null
     */
    private String getOutAuthorizationNo(String openid) {
        // 1. 优先从 Redis 取
        String info = redis.opsForValue().get(String.format(TRANSFER_AUTH_INFO_KEY, openid));
        if (StringUtils.isNotBlank(info)) {
            String[] parts = info.split("\\|");
            if (parts.length >= 1 && StringUtils.isNotBlank(parts[0])) {
                return parts[0];
            }
        }
        // 2. 降级从 DB 取
        try {
            User user = userMapper.selectByOpenid(openid);
            if (user != null && StringUtils.isNotBlank(user.getOutAuthorizationNo())) {
                // 回填 Redis
                String authId = user.getAuthorizationId() == null ? "" : user.getAuthorizationId();
                redis.opsForValue().set(String.format(TRANSFER_AUTH_INFO_KEY, openid),
                        user.getOutAuthorizationNo() + "|" + authId, 30, TimeUnit.DAYS);
                return user.getOutAuthorizationNo();
            }
        } catch (Exception e) {
            log.warn("从 DB 获取 outAuthorizationNo 失败: openid={}", openid, e);
        }
        return null;
    }

    /**
     * 用户主动解除免确认收款授权
     * 调用微信解除授权接口, 成功后清理本地状态 (Redis + DB)
     * 微信会异步回调 transferAuthNotify (event_type=MCHTRANSFER.AUTHORIZATION.CLOSED)
     * 参考文档: https://pay.weixin.qq.com/doc/v3/merchant/4015653811
     */
    public Map<String, Object> terminateTransferAuth(String openid) {
        // 1. 取本地 outAuthorizationNo (Redis 优先, DB 兜底)
        String outAuthorizationNo = null;
        String info = redis.opsForValue().get(String.format(TRANSFER_AUTH_INFO_KEY, openid));
        if (StringUtils.isNotBlank(info)) {
            String[] parts = info.split("\\|");
            outAuthorizationNo = parts[0];
        }
        if (StringUtils.isBlank(outAuthorizationNo)) {
            try {
                User user = userMapper.selectByOpenid(openid);
                if (user != null) {
                    outAuthorizationNo = user.getOutAuthorizationNo();
                }
            } catch (Exception e) {
                log.warn("DB 查询 outAuthorizationNo 失败: openid={}", openid, e);
            }
        }
        if (StringUtils.isBlank(outAuthorizationNo)) {
            throw new BusinessException("未找到授权记录, 无需解除");
        }

        // 2. 调微信解除授权接口
        try {
            Map<String, String> resp = wxPayService.terminateTransferAuthorization(outAuthorizationNo);
            if (!"SUCCESS".equals(resp.get("return_code"))) {
                throw new BusinessException("解除授权失败: " + resp.get("err_code_des"));
            }
            // 3. 清理本地状态 (Redis + DB); 微信还会异步回调 CLOSED, 这里先清是加快状态同步
            redis.delete(String.format(TRANSFER_AUTH_STATE_KEY, openid));
            redis.delete(String.format(TRANSFER_AUTH_INFO_KEY, openid));
            redis.delete(String.format(TRANSFER_AUTH_PKG_KEY, openid));
            try {
                userMapper.clearAuthorization(openid);
            } catch (Exception dbEx) {
                log.warn("DB 清除授权失败 (Redis 已清, 不影响): openid={}", openid, dbEx);
            }

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("state", resp.get("state"));
            result.put("closeReason", resp.get("close_reason"));
            result.put("message", "授权已解除, 后续提现需重新授权");
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解除免确认授权异常: openid={}", openid, e);
            throw new BusinessException("解除授权异常: " + e.getMessage());
        }
    }
}
