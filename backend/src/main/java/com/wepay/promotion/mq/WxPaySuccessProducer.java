package com.wepay.promotion.mq;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 微信支付成功消息生产者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxPaySuccessProducer {
    public static final String TOPIC = "WX_PAY_SUCCESS_TOPIC";
    public static final String TAG = "commission";

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 发送支付成功消息
     */
    public void send(WxPaySuccessMessage msg) {
        String destination = TOPIC + ":" + TAG;
        String json = JSON.toJSONString(msg);
        Message<String> message = MessageBuilder.withPayload(json)
                .setHeader("KEYS", msg.getOrderNo())
                .build();
        try {
            rocketMQTemplate.syncSend(destination, message);
            log.info("发送支付成功消息: orderNo={}, openid={}", msg.getOrderNo(), msg.getOpenid());
        } catch (Exception e) {
            log.error("发送支付成功消息失败: orderNo={}", msg.getOrderNo(), e);
            throw new RuntimeException("发送MQ消息失败", e);
        }
    }
}
