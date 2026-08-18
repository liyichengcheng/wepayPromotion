package com.wepay.promotion.controller;

import com.wepay.promotion.common.Result;
import com.wepay.promotion.dto.ApplyWithdrawRequest;
import com.wepay.promotion.dto.IncomeSummaryVO;
import com.wepay.promotion.interceptor.AuthInterceptor;
import com.wepay.promotion.service.IncomeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.Map;

@Slf4j
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
     * 已授权免确认用户: 转账后直接到账零钱
     * 未授权用户: 走用户确认收款模式 (需在微信内点击确认)
     */
    @PostMapping("/applyWithdraw")
    public Result<Void> applyWithdraw(@RequestBody ApplyWithdrawRequest req, HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        incomeService.applyWithdraw(openid, req.getAmount());
        return Result.success();
    }

    /**
     * 申请免确认收款授权(需登录)
     * 返回 package_info, 小程序端用 wx.requestMerchantTransfer 拉起微信授权页
     * 授权后 (TAKING_EFFECT) 后续提现直接到账零钱, 无需用户确认
     */
    @PostMapping("/applyTransferAuth")
    public Result<Map<String, Object>> applyTransferAuth(HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        return Result.success(incomeService.applyTransferAuth(openid));
    }

    /**
     * 查询免确认授权状态(需登录)
     */
    @GetMapping("/transferAuthStatus")
    public Result<Map<String, Object>> transferAuthStatus(HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        return Result.success(incomeService.getTransferAuthStatus(openid));
    }

    /**
     * 解除免确认收款授权(需登录)
     * 调用微信解除授权接口, 清理本地状态
     * 解除后用户需重新申请授权才能走免确认转账
     *
     * 参考文档: https://pay.weixin.qq.com/doc/v3/merchant/4015653811
     */
    @PostMapping("/terminateTransferAuth")
    public Result<Map<String, Object>> terminateTransferAuth(HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        return Result.success(incomeService.terminateTransferAuth(openid));
    }

    /**
     * 免确认授权结果异步通知 (由微信服务器调用, 无需登录)
     * 微信在用户确认/关闭/取消/过期授权后回调
     * 回调流程:
     * 1. 验签 (Wechatpay-Signature)
     * 2. 解密 (resource.ciphertext, AES-GCM-256, APIv3 密钥)
     * 3. 解析明文, 更新 DB + Redis
     * 参考文档: https://pay.weixin.qq.com/doc/v3/merchant/4014512908
     */
    @PostMapping("/transferAuthNotify")
    public String transferAuthNotify(HttpServletRequest request) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = request.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            String body = sb.toString();
            // 取验签头 (微信支付公钥ID 或 平台证书序列号)
            String wechatpayTimestamp = request.getHeader("Wechatpay-Timestamp");
            String wechatpayNonce = request.getHeader("Wechatpay-Nonce");
            String wechatpaySerial = request.getHeader("Wechatpay-Serial");
            String wechatpaySignature = request.getHeader("Wechatpay-Signature");
            log.info("收到免确认授权结果通知: serial={}, timestamp={}, body={}", wechatpaySerial, wechatpayTimestamp, body);
            return incomeService.handleTransferAuthNotify(body, wechatpayTimestamp, wechatpayNonce, wechatpaySerial, wechatpaySignature);
        } catch (Exception e) {
            log.error("处理免确认授权通知异常", e);
            return "{\"code\":\"FAIL\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}