package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.Withdraw;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WithdrawMapper {
    int insert(Withdraw withdraw);

    /** 已成功提现金额: status = 2 */
    Long sumWithdrawnByUser(@Param("openid") String openid);

    /** 处理中提现金额: status in (0, 1, 4) 待处理+处理中+待审核 */
    Long sumPendingByUser(@Param("openid") String openid);

    /** 当日提现累计(不含失败): 用于风控 */
    Long sumTodayByUser(@Param("openid") String openid);

    /**
     * 更新提现单状态
     */
    int updateStatus(@Param("openid") String openid,
                     @Param("id") Long id,
                     @Param("status") Integer status);

    /**
     * 更新transferNo和状态(转账成功后回填)
     */
    int updateTransferNo(@Param("openid") String openid,
                        @Param("id") Long id,
                        @Param("transferNo") String transferNo,
                        @Param("status") Integer status);

    /**
     * 按ID查询提现单(需分片键openid用于精准路由)
     */
    Withdraw selectById(@Param("openid") String openid, @Param("id") Long id);

    /**
     * 查询所有待审核/处理中的提现单(广播查询, 用于后台管理)
     */
    List<Withdraw> selectPendingReview();

    /**
     * 查询所有处理中(status=1)的提现单(广播查询, 用于转账状态查询Tab)
     */
    List<Withdraw> selectProcessingOnly();

    /**
     * 查询所有提现单(广播查询, 用于后台管理)
     */
    List<Withdraw> selectAll();

    /**
     * 按transferNo查询(需分片键openid精准路由)
     */
    Withdraw selectByTransferNo(@Param("openid") String openid, @Param("transferNo") String transferNo);
}
