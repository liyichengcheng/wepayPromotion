package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.CommissionDetail;
import org.apache.ibatis.annotations.Param;

public interface CommissionDetailMapper {
    int insert(CommissionDetail detail);

    /**
     * 按订单号+获利者openid查询佣金明细
     * @param openid  获利者openid(分片键,必填)
     * @param orderNo 订单号
     */
    CommissionDetail selectByOrderNo(@Param("openid") String openid,
                                     @Param("orderNo") String orderNo);

    /** 待提现金额: transfer_time IS NULL */
    Long sumPendingCommissionByUser(@Param("openid") String openid);

    /** 已成功提现的金额: transfer_time IS NOT NULL */
    Long sumTransferredByUser(@Param("openid") String openid);

    /** 今日推广人数 */
    int countTodayByUser(@Param("openid") String openid);

    /** 批量将用户待提现佣金更新为已提现 */
    int batchUpdateToTransferred(@Param("openid") String openid,
                                 @Param("paymentNo") String paymentNo,
                                 @Param("transferTime") java.util.Date transferTime);
}
