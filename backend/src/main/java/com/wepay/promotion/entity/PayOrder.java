package com.wepay.promotion.entity;

import lombok.Data;
import java.util.Date;

@Data
public class PayOrder {
    private Long id;
    private String orderNo;
    /** 支付者openid(分片键) */
    private String openid;
    private Long articleId;
    private Integer payPrice;
    private String parentShareUid;
    private Integer status;
    private String prepayId;
    private String transactionId;
    private Date payTime;
    private Date createTime;
    private Date updateTime;

    @Override
    public String toString() {
        return "PayOrder{" +
                "id=" + id +
                ", orderNo='" + orderNo + '\'' +
                ", openid='" + openid + '\'' +
                ", articleId=" + articleId +
                ", payPrice=" + payPrice +
                ", parentShareUid='" + parentShareUid + '\'' +
                ", status=" + status +
                ", prepayId='" + prepayId + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", payTime=" + payTime +
                ", createTime=" + createTime +
                ", updateTime=" + updateTime +
                '}';
    }
}
