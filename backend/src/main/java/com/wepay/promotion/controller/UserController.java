package com.wepay.promotion.controller;

import com.wepay.promotion.common.Result;
import com.wepay.promotion.dto.WxLoginRequest;
import com.wepay.promotion.dto.WxLoginVO;
import com.wepay.promotion.interceptor.AuthInterceptor;
import com.wepay.promotion.service.RealNameService;
import com.wepay.promotion.service.UserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;
    private final RealNameService realNameService;
    public UserController(UserService userService, RealNameService realNameService) {
        this.userService = userService;
        this.realNameService = realNameService;
    }

    /**
     * 小程序登录: code 换 openid, 创建/查询用户, 返回 openid/token/分享链接
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
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        Map<String, String> data = new HashMap<>();
        data.put("shareLink", userService.buildShareLink(openid));
        return Result.success(data);
    }

    /**
     * 提交实名认证信息 (需登录)
     * 用户累计提现达阈值后, 需提交姓名/手机号/身份证号 + 身份证正反面图片
     * 管理员审核通过后(status→1)方可继续提现
     * Content-Type: multipart/form-data
     */
    @PostMapping("/submitRealName")
    public Result<String> submitRealName(@RequestParam(required = true) String name,
                                         @RequestParam(required = true) String phoneNo,
                                         @RequestParam(required = true) String idcardNo,
                                         @RequestParam(value = "frontImg", required = true) MultipartFile frontImg,
                                         @RequestParam(value = "backImg", required = true) MultipartFile backImg,
                                         HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        return Result.success(realNameService.submitRealName(openid, name, phoneNo, idcardNo, frontImg, backImg));
    }

    /**
     * 获取当前用户实名信息 + 身份证图片base64 (需登录)
     * 前端加载实名页时回显:
     *   status=1 已认证 → 不可再提交, 提示可继续提现
     *   status=0 且有name 已提交待审核 → 提示添加客服微信
     *   status=0 且无name 未提交 → 显示表单
     */
    @GetMapping("/realNameInfo")
    public Result<Map<String, Object>> realNameInfo(HttpServletRequest request) {
        String openid = (String) request.getAttribute(AuthInterceptor.CURRENT_OPENID);
        return Result.success(realNameService.getRealNameInfo(openid));
    }
}