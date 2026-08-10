package com.wepay.promotion.service;

import com.wepay.promotion.mapper.PayOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ArticleService {

    private static final String PAY_TOTAL_KEY = "article:paytotal:%s";

    private final PayOrderMapper payOrderMapper;
    private final StringRedisTemplate redis;

    public ArticleService(PayOrderMapper payOrderMapper, StringRedisTemplate redis) {
        this.payOrderMapper = payOrderMapper;
        this.redis = redis;
    }

    /**
     * 获取文章累计已支付用户数(用于前端定价展示)
     * 使用 Redis 原子计数器，避免分库分表下的跨分片查询
     */
    public Map<String, Object> getPayTotal(Long articleId) {
        String key = String.format(PAY_TOTAL_KEY, articleId);
        String cached = redis.opsForValue().get(key);
        int total;
        if (cached != null) {
            total = Integer.parseInt(cached);
        } else {
            total = 0;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("totalPayUser", total);
        return data;
    }

    /**
     * 支付成功后原子递增计数器
     */
    public void incrementPayTotal(Long articleId) {
        String key = String.format(PAY_TOTAL_KEY, articleId);
        Long count = redis.opsForValue().increment(key);
        log.info("文章[{}]付费人数+1, 当前总数={}", articleId, count);
    }
}
