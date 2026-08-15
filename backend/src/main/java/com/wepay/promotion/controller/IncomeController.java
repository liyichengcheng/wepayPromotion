package com.wepay.promotion.controller;

import com.wepay.promotion.common.Result;
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
     * 自动结算全部可提现余额, 风控规则: 单笔>500元或当日累计>=1000元需审核
     */
    @PostMapping("/applyWithdraw")
    public Result<Void> applyWithdraw(HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        incomeService.applyWithdraw(openid);
        return Result.success();
    }
}
