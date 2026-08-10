package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.CommissionSummary;
import org.apache.ibatis.annotations.Param;

public interface CommissionSummaryMapper {

    CommissionSummary selectByUserId(@Param("userId") String userId);

    /** 不存在则插入，存在则忽略 (用于初始化) */
    int insertIgnore(CommissionSummary summary);

    /** 增加总佣金额(佣金到账时调用) */
    int incrementTotalAmount(@Param("userId") String userId, @Param("amount") int amount);

    /** 增加提现中金额(发起提现时调用) */
    int incrementPendingAmount(@Param("userId") String userId, @Param("amount") int amount);

    /** 提现成功: 减少提现中金额，增加已提现金额 */
    int markWithdrawSuccess(@Param("userId") String userId, @Param("amount") int amount);

    /** 提现失败: 减少提现中金额 */
    int markWithdrawFailed(@Param("userId") String userId, @Param("amount") int amount);

    /** 获取可提现余额 = totalAmount - withdrawnAmount - pendingAmount */
    Long getWithdrawableAmount(@Param("userId") String userId);
}
