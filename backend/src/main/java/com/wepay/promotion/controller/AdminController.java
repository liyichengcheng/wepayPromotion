package com.wepay.promotion.controller;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.common.Result;
import com.wepay.promotion.entity.Withdraw;
import com.wepay.promotion.mapper.WithdrawMapper;
import com.wepay.promotion.service.AdminPasswordService;
import com.wepay.promotion.service.IncomeService;
import com.wepay.promotion.service.WxPayService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 后台管理控制器 (管理员端)
 * Basic Auth: 用户名admin, 密码存储于Redis(优先)或application.yml
 * <p>
 * 所有接口通过 adminExecutor 异步执行, 与用户操作接口 (Tomcat 默认线程池) 隔离,
 * 防止管理员侧耗时操作耗尽用户侧线程.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {
    private final WithdrawMapper withdrawMapper;
    private final IncomeService incomeService;
    private final WxPayService wxPayService;
    private final AdminPasswordService adminPasswordService;
    private final Executor adminExecutor;

    public AdminController(WithdrawMapper withdrawMapper,
                          IncomeService incomeService,
                          WxPayService wxPayService,
                          AdminPasswordService adminPasswordService,
                          @Qualifier("adminExecutor") Executor adminExecutor) {
        this.withdrawMapper = withdrawMapper;
        this.incomeService = incomeService;
        this.wxPayService = wxPayService;
        this.adminPasswordService = adminPasswordService;
        this.adminExecutor = adminExecutor;
    }

    /** 获取待审核/处理中的提现列表 (含0,1,4) */
    @GetMapping("/withdraw/pending")
    public CompletableFuture<Result<List<Withdraw>>> listPending() {
        return CompletableFuture.supplyAsync(() -> Result.success(withdrawMapper.selectPendingReview()), adminExecutor);
    }

    /** 获取所有提现记录 */
    @GetMapping("/withdraw/list")
    public CompletableFuture<Result<List<Withdraw>>> listAll() {
        return CompletableFuture.supplyAsync(() -> Result.success(withdrawMapper.selectAll()), adminExecutor);
    }

    /** 获取处理中(status=1)的提现列表 (转账状态查询Tab默认数据) */
    @GetMapping("/withdraw/processing")
    public CompletableFuture<Result<List<Withdraw>>> listProcessing() {
        return CompletableFuture.supplyAsync(() -> Result.success(withdrawMapper.selectProcessingOnly()), adminExecutor);
    }

    /** 审核通过提现申请 */
    @PostMapping("/withdraw/approve")
    @Deprecated
    public CompletableFuture<Result<Void>> approve(@RequestBody Map<String, Object> body) {
        String openid = (String) body.get("openid");
        Long withdrawId = getLong(body, "withdrawId");
        if (openid == null || withdrawId == null) {
            throw new BusinessException("openid 和 withdrawId 不能为空");
        }
        return CompletableFuture.supplyAsync(() -> {
            incomeService.adminApproveAndWithdraw(openid, withdrawId);
            return Result.<Void>success();
        }, adminExecutor);
    }

    /** 拒绝提现申请 */
    @PostMapping("/withdraw/reject")
    public CompletableFuture<Result<Void>> reject(@RequestBody Map<String, Object> body) {
        String openid = (String) body.get("openid");
        Long withdrawId = getLong(body, "withdrawId");
        if (openid == null || withdrawId == null) {
            throw new BusinessException("openid 和 withdrawId 不能为空");
        }
        return CompletableFuture.supplyAsync(() -> {
            incomeService.adminRejectWithdraw(openid, withdrawId);
            return Result.<Void>success();
        }, adminExecutor);
    }

    /** 手动查询微信转账状态 */
    @GetMapping("/withdraw/queryTransfer")
    public CompletableFuture<Result<String>> queryTransfer(@RequestParam String openid, @RequestParam Long withdrawId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Withdraw withdraw = withdrawMapper.selectById(openid, withdrawId);
                String transferNo = withdraw.getTransferNo();
                int amountFen = withdraw.getAmount();
                String result = incomeService.queryAndHandlerTransferWithBackoff(withdrawId, openid, transferNo, amountFen, true);
                return Result.success(result);
            } catch (BusinessException e) {
                return Result.fail(e.getCode(), e.getMessage());
            } catch (Exception e) {
                log.error("查询转账状态失败: openid={}, withdrawId={}", openid, withdrawId, e);
                return Result.fail("查询转账状态失败: " + e.getMessage());
            }
        }, adminExecutor);
    }

    /** 手动重试: 根据 transferNo 查询微信状态并更新结果 */
    @PostMapping("/withdraw/retry")
    @Deprecated
    public CompletableFuture<Result<Map<String, Object>>> retry(@RequestBody Map<String, Object> body) {
        String openid = (String) body.get("openid");
        Long withdrawId = getLong(body, "withdrawId");
        if (openid == null || withdrawId == null) {
            throw new BusinessException("openid 和 withdrawId 不能为空");
        }
        Withdraw withdraw = withdrawMapper.selectById(openid, withdrawId);
        if (withdraw == null) {
            throw new BusinessException("提现单不存在");
        }
        if (StringUtils.isBlank(withdraw.getTransferNo())) {
            throw new BusinessException("该提现单没有transferNo, 无法查询状态");
        }
        final String transferNo = withdraw.getTransferNo();
        final int amountFen = withdraw.getAmount();
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> result = new HashMap<>();
            try {
                Map<String, String> resp = wxPayService.queryTransferStatus(transferNo);
                result.put("queryResp", resp);
                String returnCode = resp.get("return_code");
                String resultCode = resp.get("result_code");
                String transferStatus = resp.get("transfer_status");
                if ("SUCCESS".equals(returnCode) && "SUCCESS".equals(resultCode)) {
                    if ("SUCCESS".equals(transferStatus)) {
                        incomeService.handleTransferSuccess(openid, withdrawId, transferNo, amountFen);
                        result.put("action", "updated_to_success");
                    } else if ("FAIL".equals(transferStatus)) {
                        incomeService.handleTransferFailed(openid, withdrawId, transferNo, amountFen);
                        result.put("action", "updated_to_failed");
                    } else {
                        result.put("action", "still_processing");
                    }
                } else {
                    result.put("action", "query_failed");
                }
            } catch (Exception e) {
                log.error("重试查询转账状态失败: openid={}, withdrawId={}", openid, withdrawId, e);
                return Result.fail("查询转账状态失败: " + e.getMessage());
            }
            return Result.success(result);
        }, adminExecutor);
    }

    /**
     * 重新发起提现 (针对status=1处理中但结果不明的提现单)
     * 流程: 若已有transferNo, 先查微信状态; SUCCESS直接成功/FAIL再重新调用转账(新transferNo)
     */
    @PostMapping("/withdraw/reInitiate")
    public CompletableFuture<Result<Map<String, Object>>> reInitiate(@RequestBody Map<String, Object> body) {
        String openid = (String) body.get("openid");
        Long withdrawId = getLong(body, "withdrawId");
        if (openid == null || withdrawId == null) {
            throw new BusinessException("openid 和 withdrawId 不能为空");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> result = incomeService.reInitiateWithdraw(openid, withdrawId);
                return Result.success(result);
            } catch (BusinessException e) {
                return Result.fail(e.getCode(), e.getMessage());
            } catch (Exception e) {
                log.error("重新发起提现失败: openid={}, withdrawId={}", openid, withdrawId, e);
                return Result.fail("重新发起提现失败: " + e.getMessage());
            }
        }, adminExecutor);
    }

    /* ===================== 管理员密码相关 ===================== */
    /** 获取 RSA 公钥(PEM格式) - 前端用来加密新密码 */
    @GetMapping("/publicKey")
    public CompletableFuture<Result<String>> getPublicKey() {
        return CompletableFuture.supplyAsync(() -> Result.success(adminPasswordService.getPublicKeyPem()), adminExecutor);
    }

    /**
     * 修改管理员密码
     * body:
     *   oldPassword: 明文原密码 (校验)
     *   encryptedNewPassword: RSA公钥加密后的新密码 (Base64)
     */
    @PostMapping("/changePassword")
    public CompletableFuture<Result<Void>> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body.get("oldPassword");
        String encryptedNewPassword = body.get("encryptedNewPassword");
        if (StringUtils.isBlank(oldPassword)) {
            throw new BusinessException("请输入原密码");
        }
        if (StringUtils.isBlank(encryptedNewPassword)) {
            throw new BusinessException("请输入新密码");
        }
        final String finalEncryptedNewPassword = encryptedNewPassword;
        return CompletableFuture.supplyAsync(() -> {
            adminPasswordService.changePassword(oldPassword, finalEncryptedNewPassword);
            log.info("管理员密码已修改");
            return Result.<Void>success();
        }, adminExecutor);
    }

    private Long getLong(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null) return null;
        if (val instanceof Long) return (Long) val;
        if (val instanceof Integer) return ((Integer) val).longValue();
        if (val instanceof String) return Long.parseLong((String) val);
        return null;
    }
}
