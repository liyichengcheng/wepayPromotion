package com.wepay.promotion.service;

import com.wepay.promotion.entity.CommissionDetail;
import com.wepay.promotion.entity.CommissionSummary;
import com.wepay.promotion.entity.PayOrder;
import com.wepay.promotion.entity.User;
import com.wepay.promotion.mapper.CommissionDetailMapper;
import com.wepay.promotion.mapper.CommissionSummaryMapper;
import com.wepay.promotion.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * @author sambee
 * @description
 * @createDate 2026/8/13 15:40
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionService {
    private static final double COMMISSION_RATE = 0.3;
    private static final int COMMISSION_MIN_FEN = 200; // 2元

    private final UserMapper userMapper;
    private final CommissionDetailMapper commissionDetailMapper;
    private final CommissionSummaryMapper commissionSummaryMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handleCommission(PayOrder order) throws Exception {
        String orderNo = order.getOrderNo();
        String parentShareUid = order.getParentShareUid();

        if (StringUtils.isBlank(parentShareUid)) {
            log.info("订单[{}]无分享者，跳过佣金处理", orderNo);
            return;
        }
        // parentShareUid 即为分享者openid(分片键)
        // 幂等: 按分片键 (parentShareUid=获利者openid) + orderNo 精准路由检查
        CommissionDetail existing = commissionDetailMapper.selectByOrderNo(parentShareUid, orderNo);
        if (existing != null) {
            log.info("订单[{}]佣金已处理，跳过", orderNo);
            return;
        }

        // 获取分享者信息 (parentShareUid 是 openid, 直接精准路由)
        User referrer = userMapper.selectByOpenid(parentShareUid);
        if (referrer == null || referrer.getOpenid() == null) {
            log.warn("分享者不存在: parentShareUid={}", parentShareUid);
            return;
        }

        // 计算佣金
        int totalFee = order.getPayPrice();
        int commissionFen = (int) Math.round(totalFee * COMMISSION_RATE);
        if (commissionFen < COMMISSION_MIN_FEN) {
            commissionFen = COMMISSION_MIN_FEN;
        }

        // 1. 插入佣金明细
        CommissionDetail detail = new CommissionDetail();
        detail.setOpenid(referrer.getOpenid());
        detail.setFromOpenid(order.getOpenid());
        detail.setOrderNo(orderNo);
        detail.setPayAmount(totalFee);
        detail.setCommissionAmount(commissionFen);
        detail.setTransferTime(null); //待提现时更新
        commissionDetailMapper.insert(detail);

        // 2. 初始化或更新佣金汇总
        ensureSummary(referrer);
        commissionSummaryMapper.incrementTotalAmount(referrer.getOpenid(), commissionFen);

        log.info("佣金处理完成: 分享者[{}]获得佣金{}分, 订单号={}", referrer.getOpenid(), commissionFen, orderNo);
    }

    /**
     * 确保用户有佣金汇总记录
     */
    private void ensureSummary(User referrer) {
        CommissionSummary summary = commissionSummaryMapper.selectByOpenid(referrer.getOpenid());
        if (summary == null) {
            summary = new CommissionSummary();
            summary.setOpenid(referrer.getOpenid());
            summary.setTotalAmount(0);
            summary.setPendingAmount(0);
            summary.setWithdrawnAmount(0);
            commissionSummaryMapper.insertIgnore(summary);
        }
    }
}