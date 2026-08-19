package com.wepay.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 * <p>
 * 管理员后台接口 (AdminController) 使用 adminExecutor,
 * 与用户操作接口 (IncomeController/PayController) 的 Tomcat 默认线程池隔离,
 * 防止管理员侧耗时操作 (如阶梯延时查询转账状态) 耗尽用户侧线程.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    /**
     * 管理员后台专用线程池
     * - 核心数 2, 最大 4, 队列 20: 管理员操作并发量低但单次耗时长
     * - CallerRunsPolicy: 池满时回落到调用线程, 保证不丢请求
     */
    @Bean("adminExecutor")
    public Executor adminExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("admin-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 用户操作专用线程池 (预留, 供 IncomeController 等后续接入)
     * - 核心数 5, 最大 10, 队列 100: 用户操作并发量高但单次耗时短
     * - AbortPolicy: 池满时拒绝并返回 503, 配合限流降级
     */
    @Bean("userExecutor")
    public Executor userExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("user-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
