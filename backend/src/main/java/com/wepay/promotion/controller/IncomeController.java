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
     * 佣金已自动转账至零钱, 此接口兼容前端保留
     */
    @PostMapping("/applyWithdraw")
    public Result<Void> applyWithdraw(@RequestBody(required = false) java.util.Map<String, String> body,
                                      HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        incomeService.applyWithdraw(openid);
        return Result.success();
    }
}
