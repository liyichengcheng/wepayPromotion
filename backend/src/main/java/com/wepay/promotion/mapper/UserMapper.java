package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.User;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {
    User selectByOpenid(@Param("openid") String openid);

    User selectByUserId(@Param("userId") String userId);

    int insert(User user);
}