package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.User;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {
    /**
     * 按openid查询用户(分片键精准路由)
     */
    User selectByOpenid(@Param("openid") String openid);

    int insert(User user);
}
