package com.wepay.promotion;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.wepay.promotion.mapper")
@EnableScheduling
public class WepayPromotionApplication {
    public static void main(String[] args) {
        SpringApplication.run(WepayPromotionApplication.class, args);
    }
}