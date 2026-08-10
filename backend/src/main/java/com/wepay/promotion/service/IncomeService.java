package com.wepay.promotion.service;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.dto.IncomeSummaryVO;
import com.wepay.promotion.entity.CommissionSummary;
import com.wepay.promotion.entity.User;
import com.wepay.promotion.entity.Withdraw;
import com.wepay.promotion.mapper.CommissionDetailMapper;
import com.wepay.promotion.mapper.CommissionSummaryMapper;
import com.wepay.promotion.mapper.UserMapper;
import com.wepay.promotion.mapper.WithdrawMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class IncomeService {

    private static final long ARTICLE_ID = 10001L;
    private static final int BASE_PRICE = 6;
    private static final int MAX_PRICE = 20;
    private static final double COMMISSION_RATE = 0.3;
    private static final double COMMISSION_MIN = 2.0;
    private static final int MIN_WITHDRAW_FEN = 100;

    private final CommissionSummaryMapper commissionSummaryMapper;
    private final CommissionDetailMapper commissionDetailMapper;
    private final WithdrawMapper withdrawMapper;
    private final UserMapper userMapper;
    private final WxPayService wxPayService;
    private final ArticleService articleService;

    public IncomeService(CommissionSummaryMapper commissionSummaryMapper,
                        CommissionDetailMapper commissionDetailMapper,
                        WithdrawMapper withdrawMapper,
                        UserMapper userMapper, WxPayService wxPayService,
                        ArticleService articleService) {
        this.commissionSummaryMapper = commissionSummaryMapper;
        this.commissionDetailMapper = commissionDetailMapper;
        this.withdrawMapper = withdrawMapper;
        this.userMapper = userMapper;
        this.wxPayService = wxPayService;
        this.articleService = articleService;
    }

    public IncomeSummaryVO getUserIncome(String userId) {
        CommissionSummary summary = commissionSummaryMapper.selectByUserId(userId);
        long totalFen;
        long withdrawableFen;

        if (summary != null) {
            totalFen = summary.getTotalAmount();
            withdrawableFen = Math.max(0,
                    summary.getTotalAmount() - summary.getWithdrawnAmount() - summary.getPendingAmount());
        } else {
            log.warn("佣金汇总不存在, 回落到明细表计算: userId={}", userId);
            long pendingCommissionFen = commissionDetailMapper.sumPendingCommissionByUser(userId);
            long withdrawnFen = commissionDetailMapper.sumTransferredByUser(userId);
            totalFen = pendingCommissionFen + withdrawnFen;
            User user = userMapper.selectByUserId(userId);
            if (user != null) {
                initSummary(userId, user.getOpenid(), totalFen, withdrawnFen);
            }
            withdrawableFen = pendingCommissionFen;
        }

        int todayCount = commissionDetailMapper.countTodayByUser(userId);

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

    @Transactional(rollbackFor = Exception.class)
    public void applyWithdraw(String userId) {
        User user = userMapper.selectByUserId(userId);
        if (user == null || user.getOpenid() == null) {
            throw new BusinessException("用户不存在或未绑定openid");
        }

        // 1. 获取可提现余额
        CommissionSummary summary = commissionSummaryMapper.selectByUserId(userId);
        long availableFen;
        if (summary != null) {
            availableFen = Math.max(0,
                    summary.getTotalAmount() - summary.getWithdrawnAmount() - summary.getPendingAmount());
        } else {
            long pendingCommissionFen = commissionDetailMapper.sumPendingCommissionByUser(userId);
            long pendingWithdrawFen = withdrawMapper.sumPendingByUser(userId);
            availableFen = Math.max(0, pendingCommissionFen - pendingWithdrawFen);
            initSummary(userId, user.getOpenid(), 0, 0);
            summary = commissionSummaryMapper.selectByUserId(userId);
        }

        if (availableFen < MIN_WITHDRAW_FEN) {
            throw new BusinessException("可提现余额不足, 至少1元才能提现");
        }

        // 2. 创建提现申请
        Withdraw withdraw = new Withdraw();
        withdraw.setUserId(userId);
        withdraw.setOpenid(user.getOpenid());
        withdraw.setAmount((int) availableFen);
        withdraw.setStatus(0);
        withdrawMapper.insert(withdraw);

        // 3. 更新汇总表：增加提现中金额
        commissionSummaryMapper.incrementPendingAmount(userId, (int) availableFen);

        // 4. 调用微信企业付款接口
        String transferNo = "wd_" + withdraw.getId() + "_" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Map<String, String> resp = wxPayService.transfer(
                    transferNo, user.getOpenid(), (int) availableFen,
                    "分享佣金提现", getServerIp());

            if ("SUCCESS".equals(resp.get("return_code")) && "SUCCESS".equals(resp.get("result_code"))) {
                withdrawMapper.updateStatus(userId, withdraw.getId(), 2);
                commissionSummaryMapper.markWithdrawSuccess(userId, (int) availableFen);
                commissionDetailMapper.batchUpdateToTransferred(userId,"",new Date());
                log.info("提现成功: user={}, 金额={}分, paymentNo={}", userId, availableFen, resp.get("payment_no"));
            } else {
                String failReason = resp.get("err_code") + ":" + resp.get("err_code_des");
                withdrawMapper.updateStatus(userId, withdraw.getId(), 3);
                commissionSummaryMapper.markWithdrawFailed(userId, (int) availableFen);
                throw new BusinessException("提现失败: " + failReason);
            }
        } catch (BusinessException e) {
            commissionSummaryMapper.markWithdrawFailed(userId, (int) availableFen);
            throw e;
        } catch (Exception e) {
            withdrawMapper.updateStatus(userId, withdraw.getId(), 3);
            commissionSummaryMapper.markWithdrawFailed(userId, (int) availableFen);
            log.error("提现异常: userId={}", userId, e);
            throw new BusinessException("提现处理异常, 请稍后重试");
        }
    }

    private void initSummary(String userId, String openid, long totalAmount, long withdrawnAmount) {
        CommissionSummary summary = new CommissionSummary();
        summary.setUserId(userId);
        summary.setOpenid(openid);
        summary.setTotalAmount((int) totalAmount);
        summary.setPendingAmount(0);
        summary.setWithdrawnAmount((int) withdrawnAmount);
        commissionSummaryMapper.insertIgnore(summary);
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
}
