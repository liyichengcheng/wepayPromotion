package com.wepay.promotion.service;

import com.wepay.promotion.common.BusinessException;
import com.wepay.promotion.config.IdcardConfig;
import com.wepay.promotion.entity.User;
import com.wepay.promotion.mapper.UserMapper;
import com.wepay.promotion.util.RealNameValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 实名认证服务
 * <p>
 * 用户累计提现达阈值后需提交实名信息 (姓名/手机号/身份证号 + 身份证正反面图片)
 * 管理员审核通过后 status→1, 用户方可继续提现
 */
@Slf4j
@Service
public class RealNameService {

    private final UserMapper userMapper;
    private final IdcardConfig idcardConfig;

    public RealNameService(UserMapper userMapper, IdcardConfig idcardConfig) {
        this.userMapper = userMapper;
        this.idcardConfig = idcardConfig;
    }

    /**
     * 用户提交实名信息
     * @param openid      用户openid (分片键)
     * @param name        真实姓名
     * @param phoneNo     手机号
     * @param idcardNo    身份证号
     * @param frontImg    身份证正面图片
     * @param backImg     身份证背面图片
     * @return 提示语
     */
    public String submitRealName(String openid, String name, String phoneNo, String idcardNo,
                                 MultipartFile frontImg, MultipartFile backImg) {
        // 1. 格式校验
        if (!RealNameValidator.isValidName(name)) {
            throw new BusinessException("姓名格式不正确(2-25个汉字)");
        }
        if (!RealNameValidator.isValidPhone(phoneNo)) {
            throw new BusinessException("手机号格式不正确");
        }
        if (!RealNameValidator.isValidIdcard(idcardNo)) {
            throw new BusinessException("身份证号格式不正确");
        }
        if (frontImg == null || frontImg.isEmpty()) {
            throw new BusinessException("请上传身份证正面照片");
        }
        if (backImg == null || backImg.isEmpty()) {
            throw new BusinessException("请上传身份证背面照片");
        }

        // 2. 保存身份证图片 (保留原格式)
        String frontExt = getExtension(frontImg.getOriginalFilename());
        String backExt = getExtension(backImg.getOriginalFilename());
        String frontFileName = idcardNo + "_1" + frontExt;
        String backFileName = idcardNo + "_2" + backExt;

        Path uploadPath = Paths.get(idcardConfig.getUploadDir());
        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            frontImg.transferTo(uploadPath.resolve(frontFileName).toFile());
            backImg.transferTo(uploadPath.resolve(backFileName).toFile());
            log.info("身份证图片保存成功: openid={}, front={}, back={}", openid, frontFileName, backFileName);
        } catch (IOException e) {
            log.error("身份证图片保存失败: openid={}", openid, e);
            throw new BusinessException("图片保存失败, 请重试");
        }

        // 3. 更新 t_user 表 (status 保持不变, 等待管理员审核)
        int rows = userMapper.updateRealNameInfo(openid, name, phoneNo, idcardNo);
        if (rows < 1) {
            throw new BusinessException("用户不存在, 更新失败");
        }

        // 4. 返回提示
        String wechat = idcardConfig.getCustomerWechat();
        if (wechat == null || wechat.trim().isEmpty()) {
            return "实名信息已提交, 等待审核通过后可重新申请提现";
        }
        return "实名信息已提交, 请添加客服微信 " + wechat + " 为好友, 等待实名认证后可重新申请提现";
    }

    /**
     * 后台按条件查询用户
     */
    public List<User> searchUsers(String phoneNo, String idcardNo, Integer status) {
        return userMapper.searchUsers(
                (phoneNo == null || phoneNo.trim().isEmpty()) ? null : phoneNo.trim(),
                (idcardNo == null || idcardNo.trim().isEmpty()) ? null : idcardNo.trim(),
                status);
    }

    /**
     * 管理员审核通过: status→1
     */
    public void approveRealName(String openid) {
        User user = userMapper.selectByOpenid(openid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getName() == null || user.getPhoneNo() == null || user.getIdcardNo() == null) {
            throw new BusinessException("用户尚未提交实名信息, 无法审核通过");
        }
        userMapper.updateStatus(openid, 1);
        log.info("管理员实名审核通过: openid={}", openid);
    }

    /**
     * 管理员冻结提现: status→-1
     */
    public void freezeWithdraw(String openid) {
        User user = userMapper.selectByOpenid(openid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        userMapper.updateStatus(openid, -1);
        log.info("管理员冻结用户提现: openid={}", openid);
    }

    /**
     * 管理员解冻提现: status→0
     */
    public void unfreezeWithdraw(String openid) {
        User user = userMapper.selectByOpenid(openid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        userMapper.updateStatus(openid, 0);
        log.info("管理员解冻用户提现: openid={}", openid);
    }

    /**
     * 加载身份证图片 (供后台显示)
     * @param openid  用户openid
     * @param side    1=正面, 2=背面
     * @return 图片字节数组, 不存在返回 null
     */
    public byte[] loadIdcardImage(String openid, int side) {
        User user = userMapper.selectByOpenid(openid);
        if (user == null || user.getIdcardNo() == null) {
            return null;
        }
        String idcardNo = user.getIdcardNo();
        String dir = idcardConfig.getUploadDir();
        // 尝试常见扩展名
        String[] exts = {".jpg", ".jpeg", ".png"};
        for (String ext : exts) {
            File imgFile = Paths.get(dir, idcardNo + "_" + side + ext).toFile();
            if (imgFile.exists()) {
                try {
                    return Files.readAllBytes(imgFile.toPath());
                } catch (IOException e) {
                    log.error("读取身份证图片失败: {}", imgFile.getPath(), e);
                    return null;
                }
            }
        }
        return null;
    }

    /** 从原始文件名提取扩展名 (含.), 如 .jpg / .png, 无扩展名时返回空字符串 */
    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = filename.substring(dot).toLowerCase();
        // 只允许 jpg/jpeg/png
        if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png")) {
            return ext;
        }
        return ".jpg"; // 默认 jpg
    }
}
