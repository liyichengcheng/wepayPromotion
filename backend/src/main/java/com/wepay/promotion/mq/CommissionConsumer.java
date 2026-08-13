//package com.wepay.promotion.mq;
//
//import com.alibaba.fastjson.JSON;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
//import org.apache.rocketmq.spring.core.RocketMQListener;
//import org.springframework.stereotype.Component;
//import org.springframework.transaction.annotation.Transactional;
//
///**
// * 佣金消息消费者
// * 处理微信支付成功消息，生成佣金明细并更新佣金汇总
// */
//@Slf4j
//@Component
//@RequiredArgsConstructor
//@RocketMQMessageListener(
//        topic = WxPaySuccessProducer.TOPIC,
//        selectorExpression = WxPaySuccessProducer.TAG,
//        consumerGroup = "wepay-promotion-consumer-group"
//)
//public class CommissionConsumer implements RocketMQListener<String> {
//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void onMessage(String body) {
//        log.info("收到支付成功消息: {}", body);
//        WxPaySuccessMessage msg = JSON.parseObject(body, WxPaySuccessMessage.class);
//        if (msg == null || msg.getOrderNo() == null) {
//            log.warn("消息内容为空或订单号缺失");
//            return;
//        }
//    }
//}