package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.Withdraw;
import org.apache.ibatis.annotations.Param;

public interface WithdrawMapper {
    int insert(Withdraw withdraw);

    /** 已成功提现金额: status = 2 */
    Long sumWithdrawnByUser(@Param("openid") String openid);

    /** 处理中提现金额: status in (0, 1) */
    Long sumPendingByUser(@Param("openid") String openid);

    /**
     * 更新提现单状态
     * @param openid 分片键(必填,用于路由到具体分片)
     * @param id     提现单ID
     * @param status 新状态
     */
    int updateStatus(@Param("openid") String openid,
                     @Param("id") Long id,
                     @Param("status") Integer status);
}
