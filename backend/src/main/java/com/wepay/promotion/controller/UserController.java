package com.wepay.promotion.controller;

import com.wepay.promotion.common.Result;
import com.wepay.promotion.dto.WxLoginRequest;
import com.wepay.promotion.dto.WxLoginVO;
import com.wepay.promotion.interceptor.AuthInterceptor;
import com.wepay.promotion.service.UserService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 小程序登录: code 换 openid, 创建/查询用户, 返回 userId/token/分享链接
     */
    @PostMapping("/wxLogin")
    public Result<WxLoginVO> wxLogin(@RequestBody WxLoginRequest req) {
        WxLoginVO vo = userService.wxLogin(req.getCode());
        return Result.success(vo);
    }

    /**
     * 获取当前用户的专属分享链接(需登录)
     */
    @GetMapping("/shareLink")
    public Result<Map<String, String>> shareLink(HttpServletRequest request) {
        String userId = (String) request.getAttribute(AuthInterceptor.CURRENT_USER_ID);
        Map<String, String> data = new HashMap<>();
        data.put("shareLink", userService.buildShareLink(userId));
        return Result.success(data);
    }
}
