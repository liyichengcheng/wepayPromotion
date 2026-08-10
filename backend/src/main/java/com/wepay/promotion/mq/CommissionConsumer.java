package com.wepay.promotion.mq;

import com.alibaba.fastjson.JSON;
import com.wepay.promotion.entity.CommissionDetail;
import com.wepay.promotion.entity.CommissionSummary;
import com.wepay.promotion.entity.User;
import com.wepay.promotion.mapper.CommissionDetailMapper;
import com.wepay.promotion.mapper.CommissionSummaryMapper;
import com.wepay.promotion.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 佣金消息消费者
 * 处理微信支付成功消息，生成佣金明细并更新佣金汇总
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = WxPaySuccessProducer.TOPIC,
        selectorExpression = WxPaySuccessProducer.TAG,
        consumerGroup = "wepay-promotion-consumer-group"
)
public class CommissionConsumer implements RocketMQListener<String> {

    private static final double COMMISSION_RATE = 0.3;
    private static final int COMMISSION_MIN_FEN = 200; // 2元

    private final UserMapper userMapper;
    private final CommissionDetailMapper commissionDetailMapper;
    private final CommissionSummaryMapper commissionSummaryMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(String body) {
        log.info("收到支付成功消息: {}", body);
        WxPaySuccessMessage msg = JSON.parseObject(body, WxPaySuccessMessage.class);
        if (msg == null || msg.getOrderNo() == null) {
            log.warn("消息内容为空或订单号缺失");
            return;
        }

        String orderNo = msg.getOrderNo();
        String parentShareUid = msg.getParentShareUid();

        if (parentShareUid == null || parentShareUid.isEmpty()) {
            log.info("订单[{}]无分享者，跳过佣金处理", orderNo);
            return;
        }

        // 幂等: 按分片键 (parentShareUid=获利者userId) + orderNo 精准路由检查
        CommissionDetail existing = commissionDetailMapper.selectByOrderNo(parentShareUid, orderNo);
        if (existing != null) {
            log.info("订单[{}]佣金已处理，跳过", orderNo);
            return;
        }

        // 获取分享者信息
        User referrer = userMapper.selectByUserId(parentShareUid);
        if (referrer == null || referrer.getOpenid() == null) {
            log.warn("分享者不存在: parentShareUid={}", parentShareUid);
            return;
        }

        // 计算佣金
        int totalFee = msg.getPayAmount();
        int commissionFen = (int) Math.round(totalFee * COMMISSION_RATE);
        if (commissionFen < COMMISSION_MIN_FEN) {
            commissionFen = COMMISSION_MIN_FEN;
        }

        // 1. 插入佣金明细 (无 article_id/status/transfer_no 等字段，与 schema.sql 对齐)
        CommissionDetail detail = new CommissionDetail();
        detail.setUserId(referrer.getUserId());
        detail.setOpenid(referrer.getOpenid());
        detail.setFromUserId(msg.getUserId());
        detail.setOrderNo(orderNo);
        detail.setPayAmount(totalFee);
        detail.setCommissionAmount(commissionFen);
        detail.setTransferTime(null); // 待提现时更新
        commissionDetailMapper.insert(detail);

        // 2. 初始化或更新佣金汇总
        ensureSummary(referrer);
        commissionSummaryMapper.incrementTotalAmount(referrer.getUserId(), commissionFen);

        log.info("佣金处理完成: 分享者[{}]获得佣金{}分, 订单号={}", referrer.getUserId(), commissionFen, orderNo);
    }

    /**
     * 确保用户有佣金汇总记录
     */
    private void ensureSummary(User referrer) {
        CommissionSummary summary = commissionSummaryMapper.selectByUserId(referrer.getUserId());
        if (summary == null) {
            summary = new CommissionSummary();
            summary.setUserId(referrer.getUserId());
            summary.setOpenid(referrer.getOpenid());
            summary.setTotalAmount(0);
            summary.setPendingAmount(0);
            summary.setWithdrawnAmount(0);
            commissionSummaryMapper.insertIgnore(summary);
        }
    }
}
