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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.util.Date;
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
    private static final int MIN_WITHDRAW_FEN = 100;

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
//        if (amountFen == null || amountFen < MIN_WITHDRAW_FEN) {
//            throw new BusinessException("提现金额至少1元");
//        } //todo

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
        withdraw.setStatus(status); // 处理中
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
     */
    @Transactional(rollbackFor = Exception.class)
    public void executeTransfer(String openid, Withdraw withdraw, int amountFen) {
        String ip = getServerIp();
        boolean apiCalled = false;

        Long withdrawId = withdraw.getId();
        String transferNo = withdraw.getTransferNo();
        try {
            Map<String, String> resp = wxPayService.transfer(
                    transferNo, openid, amountFen, "分享佣金提现", ip);
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
        } catch (BusinessException e) {
            log.error("",e);
            throw e;
        } catch (Exception e) {
            if (apiCalled) {
                // 接口有响应但处理异常
                log.error("提现处理异常: openid={}, transferNo={}", openid, transferNo, e);
                handleTransferFailed(openid, withdrawId, transferNo, amountFen);
                throw new BusinessException("提现处理异常, 请稍后重试");
            } else {
                // 接口无响应: 阶梯延时查询
                log.warn("转账接口无响应, 开始阶梯延时查询: openid={}, transferNo={}", openid, transferNo, e);
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
        if (existingTransferNo != null && !existingTransferNo.isEmpty()) {
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
            log.warn("释放提现锁失败 lockKey={}", lockKey, e);
        }
    }
}
