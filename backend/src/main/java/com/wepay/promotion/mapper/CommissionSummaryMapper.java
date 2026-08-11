package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.CommissionSummary;
import org.apache.ibatis.annotations.Param;

public interface CommissionSummaryMapper {

    /**
     * 按openid查询(分片键精准路由)
     */
    CommissionSummary selectByOpenid(@Param("openid") String openid);

    /** 不存在则插入，存在则忽略 (用于初始化) */
    int insertIgnore(CommissionSummary summary);

    /** 增加总佣金额(佣金到账时调用) */
    int incrementTotalAmount(@Param("openid") String openid, @Param("amount") int amount);

    /** 增加提现中金额(发起提现时调用) */
    int incrementPendingAmount(@Param("openid") String openid, @Param("amount") int amount);

    /** 提现成功: 减少提现中金额，增加已提现金额 */
    int markWithdrawSuccess(@Param("openid") String openid, @Param("amount") int amount);

    /** 提现失败: 减少提现中金额 */
    int markWithdrawFailed(@Param("openid") String openid, @Param("amount") int amount);

    /** 获取可提现余额 = totalAmount - withdrawnAmount - pendingAmount */
    Long getWithdrawableAmount(@Param("openid") String openid);
}
