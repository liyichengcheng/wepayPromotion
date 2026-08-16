package com.wepay.promotion.controller;

import com.wepay.promotion.common.Result;
import com.wepay.promotion.dto.ApplyWithdrawRequest;
import com.wepay.promotion.dto.IncomeSummaryVO;
import com.wepay.promotion.interceptor.AuthInterceptor;
import com.wepay.promotion.service.IncomeService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/income")
public class IncomeController {

    private final IncomeService incomeService;

    public IncomeController(IncomeService incomeService) {
        this.incomeService = incomeService;
    }

    /**
     * 获取用户佣金收益汇总(需登录)
     */
    @GetMapping("/getUserIncome")
    public Result<IncomeSummaryVO> getUserIncome(HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        return Result.success(incomeService.getUserIncome(openid));
    }

    /**
     * 申请提现(需登录)
     * 风控规则: 单笔>500元或当日累计>=1000元需审核
     * 限流: 同一openid每小时只能调用一次
     */
    @PostMapping("/applyWithdraw")
    public Result<Void> applyWithdraw(@RequestBody ApplyWithdrawRequest req, HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        incomeService.applyWithdraw(openid, req.getAmount());
        return Result.success();
    }
}