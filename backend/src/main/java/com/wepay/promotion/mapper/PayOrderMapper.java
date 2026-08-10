package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.PayOrder;
import org.apache.ibatis.annotations.Param;

public interface PayOrderMapper {
    int insert(PayOrder order);

    PayOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 更新支付成功状态
     * @param userId        分片键(必填,用于路由到具体分片)
     * @param orderNo       订单号
     * @param transactionId 微信交易号
     * @param payTime       支付时间
     */
    int updatePaySuccess(@Param("userId") String userId,
                         @Param("orderNo") String orderNo,
                         @Param("transactionId") String transactionId,
                         @Param("payTime") java.util.Date payTime);
}
