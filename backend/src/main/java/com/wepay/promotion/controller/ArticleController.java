package com.wepay.promotion.controller;

import com.wepay.promotion.common.Result;
import com.wepay.promotion.service.ArticleService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/article")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    /**
     * 获取文章累计已支付人数(用于前端定价)
     */
    @GetMapping("/getPayTotal")
    public Result<Map<String, Object>> getPayTotal(@RequestParam Long articleId) {
        return Result.success(articleService.getPayTotal(articleId));
    }
}
