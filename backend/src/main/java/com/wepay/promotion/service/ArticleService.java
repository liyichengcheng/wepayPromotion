package com.wepay.promotion.service;

import com.wepay.promotion.mapper.PayOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ArticleService {
    private static final String PAY_TOTAL_KEY = "article:paytotal:%s";
    /** 4个物理分片表 (单库多表分片: ds0.t_pay_order_0~3) */
    private static final String[] PAY_ORDER_TABLES = {"t_pay_order_0", "t_pay_order_1", "t_pay_order_2", "t_pay_order_3"};

    private final PayOrderMapper payOrderMapper;
    private final StringRedisTemplate redis;

    public ArticleService(PayOrderMapper payOrderMapper, StringRedisTemplate redis) {
        this.payOrderMapper = payOrderMapper;
        this.redis = redis;
    }

    /**
     * Spring 容器启动完成后, 从 4 个物理分片表聚合 article_id 计数, 初始化 Redis 缓存
     * 使用 ApplicationReadyEvent 确保所有 Bean (含 ShardingSphere DataSource) 初始化完成
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initPayTotalOnStartup() {
        log.info("开始初始化文章支付计数: 查询 {} 个物理分片表", PAY_ORDER_TABLES.length);
        Map<Long, Long> aggregated = new HashMap<>();
        long startTime = System.currentTimeMillis();

        for (String table : PAY_ORDER_TABLES) {
            try {
                List<Map<String, Object>> rows = payOrderMapper.countGroupByArticleId(table);
                log.info("查询 {} 完成, {} 个 article_id 分组", table, rows.size());
                for (Map<String, Object> row : rows) {
                    Long articleId = ((Number) row.get("articleId")).longValue();
                    Long cnt = ((Number) row.get("cnt")).longValue();
                    aggregated.merge(articleId, cnt, Long::sum);
                }
            } catch (Exception e) {
                log.error("查询 {} 失败, 跳过该分片", table, e);
            }
        }

        // 聚合结果写入 Redis (覆盖旧值, 数据库为最准)
        int written = 0;
        for (Map.Entry<Long, Long> entry : aggregated.entrySet()) {
            String key = String.format(PAY_TOTAL_KEY, entry.getKey());
            redis.opsForValue().set(key, String.valueOf(entry.getValue()));
            written++;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("文章支付计数初始化完成: 共 {} 个文章, 写入 {} 个 Redis key, 耗时 {}ms",
                aggregated.size(), written, elapsed);
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