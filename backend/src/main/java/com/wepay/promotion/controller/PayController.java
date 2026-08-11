package com.wepay.promotion.controller;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.common.Result;
import com.wepay.promotion.dto.CreateOrderRequest;
import com.wepay.promotion.dto.PayInfoVO;
import com.wepay.promotion.interceptor.AuthInterceptor;
import com.wepay.promotion.service.PayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/pay")
public class PayController {

    private final PayService payService;

    public PayController(PayService payService) {
        this.payService = payService;
    }

    /**
     * 创建支付订单(需登录)
     */
    @PostMapping("/createOrder")
    public Result<PayInfoVO> createOrder(@RequestBody CreateOrderRequest req, HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        PayInfoVO payInfo = payService.createOrder(openid, req);
        return Result.success(payInfo);
    }

    /**
     * 微信支付回调(由微信服务器调用, 无需登录)
     */
    @PostMapping("/notify")
    public void notify(HttpServletRequest request, HttpServletResponse response) {
        try {
            String xml = readBody(request);
            log.info("收到微信支付回调: {}", xml);
            String result = payService.handleNotify(xml);
            response.setContentType("text/xml;charset=UTF-8");
            response.getWriter().write(result);
        } catch (Exception e) {
            log.error("处理支付回调异常", e);
            try {
                response.setContentType("text/xml;charset=UTF-8");
                response.getWriter().write("<xml><return_code><![CDATA[FAIL]]></return_code></xml>");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 查询当前用户对文章的已支付状态是否在30天有效期内(需登录)
     */
    @GetMapping("/checkStatus")
    public Result<Map<String, Object>> checkStatus(@RequestParam Long articleId, HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        if (articleId == null) {
            throw new BusinessException("articleId不能为空");
        }
        return Result.success(payService.checkPayStatus(openid, articleId));
    }

    private String readBody(HttpServletRequest request) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
