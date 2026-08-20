package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.PayOrder;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface PayOrderMapper {
    int insert(PayOrder order);

    /**
     * 按订单号查询(广播查询，无分片键时ShardingSphere自动广播)
     */
    PayOrder selectByOpenidOrderNo(@Param("openid") String openid,@Param("orderNo") String orderNo);

    /**
     * 更新支付成功状态
     * @param openid        分片键(必填,用于路由到具体分片)
     * @param orderNo       订单号
     * @param transactionId 微信交易号
     * @param payTime       支付时间
     */
    int updatePaySuccess(@Param("openid") String openid,
                         @Param("orderNo") String orderNo,
                         @Param("transactionId") String transactionId,
                         @Param("payTime") java.util.Date payTime);

    /**
     * 按物理表名查询 article_id 分组计数 (绕过 ShardingSphere 逻辑表路由, 直接查物理表)
     * @param table 物理表名 (t_pay_order_0 ~ t_pay_order_3)
     * @return 每行包含 articleId(Long) 和 cnt(Long)
     */
    List<Map<String, Object>> countGroupByArticleId(@Param("table") String table);
}
