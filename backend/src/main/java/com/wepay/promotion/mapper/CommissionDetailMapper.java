package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.CommissionDetail;
import org.apache.ibatis.annotations.Param;

public interface CommissionDetailMapper {
    int insert(CommissionDetail detail);

    /**
     * 按订单号+获利者userId查询佣金明细
     * @param userId  获利者userId(分片键,必填)
     * @param orderNo 订单号
     */
    CommissionDetail selectByOrderNo(@Param("userId") String userId,
                                     @Param("orderNo") String orderNo);

    /** 待提现金额: status = 0 */
    Long sumPendingCommissionByUser(@Param("userId") String userId);

    /** 已成功提现的金额: status = 1 */
    Long sumTransferredByUser(@Param("userId") String userId);

    /** 今日推广人数 */
    int countTodayByUser(@Param("userId") String userId);

    /** 批量将用户待提现佣金更新为已提现 */
    int batchUpdateToTransferred(@Param("userId") String userId,
                                 @Param("paymentNo") String paymentNo,
                                 @Param("transferTime") java.util.Date transferTime);
}
