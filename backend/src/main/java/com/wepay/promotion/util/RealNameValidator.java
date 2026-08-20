package com.wepay.promotion.util;

import java.util.regex.Pattern;

/**
 * 实名信息格式校验工具类
 */
public final class RealNameValidator {

    private RealNameValidator() {}

    /** 中国姓名: 2-25个汉字, 支持少数民族间隔符· */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[\\u4e00-\\u9fa5·]{2,25}$");

    /** 手机号: 11位, 1开头, 第二位3-9 */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^1[3-9]\\d{9}$");

    /** 身份证号: 18位, 前17位数字, 末位数字或X */
    private static final Pattern IDCARD_PATTERN =
            Pattern.compile("^\\d{17}[\\dXx]$");

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public static boolean isValidPhone(String phone) {
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }

    public static boolean isValidIdcard(String idcard) {
        return idcard != null && IDCARD_PATTERN.matcher(idcard).matches();
    }
}
