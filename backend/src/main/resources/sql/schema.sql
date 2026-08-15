-- =====================================================
-- 微信支付推广后端数据库脚本（分库分表版）
-- 主库: wepay_promotion (存放文章等不分片的表 + 所有物理分片表)
-- 分片规则: HASH_MOD(openid, 4) => 表名后缀 = 哈希值
--
-- 说明: application.yml 中 ds0~ds3 均指向 wepay_promotion 库,
-- ShardingSphere 按 openid HASH_MOD(4) 计算库路由(ds0..3)和表路由(_0..3),
-- 由于所有 ds 指向同一库, 实际为单库分表(4 张物理表), 分库路由等效于别名。
--
-- 本脚本创建:
--   1. 分库分表的物理分片表 (t_user_0..3 等)
--   2. 不分片的 broadcast 表 (t_article)
-- =====================================================

CREATE DATABASE IF NOT EXISTS wepay_promotion
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE wepay_promotion;

-- =====================================================
-- 不分片的 broadcast 表
-- =====================================================

DROP TABLE IF EXISTS t_article;
CREATE TABLE t_article (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    article_id  BIGINT       NOT NULL,
    title       VARCHAR(255) NOT NULL,
    base_price  INT          NOT NULL DEFAULT 6,
    max_price   INT          NOT NULL DEFAULT 2000,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_id (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章表(不分片)';

INSERT INTO t_article (article_id, title, base_price, max_price) VALUES
(10001, '学渣逆袭：高考倒状元到百万年薪', 600, 2000);

-- =====================================================
-- 分库分表物理表: 4 个分片 (_0.._3)
-- 分片键: openid
-- 算法: HASH_MOD(openid, 4)
-- =====================================================

-- 删除旧表
DROP TABLE IF EXISTS t_user_0;
DROP TABLE IF EXISTS t_user_1;
DROP TABLE IF EXISTS t_user_2;
DROP TABLE IF EXISTS t_user_3;
DROP TABLE IF EXISTS t_pay_order_0;
DROP TABLE IF EXISTS t_pay_order_1;
DROP TABLE IF EXISTS t_pay_order_2;
DROP TABLE IF EXISTS t_pay_order_3;
DROP TABLE IF EXISTS t_commission_summary_0;
DROP TABLE IF EXISTS t_commission_summary_1;
DROP TABLE IF EXISTS t_commission_summary_2;
DROP TABLE IF EXISTS t_commission_summary_3;
DROP TABLE IF EXISTS t_commission_detail_0;
DROP TABLE IF EXISTS t_commission_detail_1;
DROP TABLE IF EXISTS t_commission_detail_2;
DROP TABLE IF EXISTS t_commission_detail_3;
DROP TABLE IF EXISTS t_withdraw_0;
DROP TABLE IF EXISTS t_withdraw_1;
DROP TABLE IF EXISTS t_withdraw_2;
DROP TABLE IF EXISTS t_withdraw_3;

-- 用户表 (4张物理分片)
CREATE TABLE t_user_0 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '微信openid',
    union_id    VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户分片表';

CREATE TABLE t_user_1 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '微信openid',
    union_id    VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户分片表';

CREATE TABLE t_user_2 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '微信openid',
    union_id    VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户分片表';

CREATE TABLE t_user_3 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '微信openid',
    union_id    VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户分片表';

-- 支付订单表 (4张物理分片)
CREATE TABLE t_pay_order_0 (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(64)  NOT NULL COMMENT '商户订单号',
    openid           VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    article_id       BIGINT       NOT NULL,
    pay_price        INT          NOT NULL COMMENT '支付金额(分)',
    parent_share_uid VARCHAR(64)  DEFAULT NULL COMMENT '分享者(上级)openid',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未支付 1=已支付 -1已退款',
    prepay_id        VARCHAR(128) DEFAULT NULL,
    transaction_id   VARCHAR(64)  DEFAULT NULL,
    pay_time         DATETIME     DEFAULT NULL,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid),
    KEY idx_parent (parent_share_uid),
    KEY idx_article (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单分片表';

CREATE TABLE t_pay_order_1 (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(64)  NOT NULL COMMENT '商户订单号',
    openid           VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    article_id       BIGINT       NOT NULL,
    pay_price        INT          NOT NULL COMMENT '支付金额(分)',
    parent_share_uid VARCHAR(64)  DEFAULT NULL COMMENT '分享者(上级)openid',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未支付 1=已支付 -1已退款',
    prepay_id        VARCHAR(128) DEFAULT NULL,
    transaction_id   VARCHAR(64)  DEFAULT NULL,
    pay_time         DATETIME     DEFAULT NULL,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid),
    KEY idx_parent (parent_share_uid),
    KEY idx_article (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单分片表';

CREATE TABLE t_pay_order_2 (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(64)  NOT NULL COMMENT '商户订单号',
    openid           VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    article_id       BIGINT       NOT NULL,
    pay_price        INT          NOT NULL COMMENT '支付金额(分)',
    parent_share_uid VARCHAR(64)  DEFAULT NULL COMMENT '分享者(上级)openid',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未支付 1=已支付 -1已退款',
    prepay_id        VARCHAR(128) DEFAULT NULL,
    transaction_id   VARCHAR(64)  DEFAULT NULL,
    pay_time         DATETIME     DEFAULT NULL,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid),
    KEY idx_parent (parent_share_uid),
    KEY idx_article (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单分片表';

CREATE TABLE t_pay_order_3 (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(64)  NOT NULL COMMENT '商户订单号',
    openid           VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    article_id       BIGINT       NOT NULL,
    pay_price        INT          NOT NULL COMMENT '支付金额(分)',
    parent_share_uid VARCHAR(64)  DEFAULT NULL COMMENT '分享者(上级)openid',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未支付 1=已支付 -1已退款',
    prepay_id        VARCHAR(128) DEFAULT NULL,
    transaction_id   VARCHAR(64)  DEFAULT NULL,
    pay_time         DATETIME     DEFAULT NULL,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid),
    KEY idx_parent (parent_share_uid),
    KEY idx_article (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单分片表';

-- 佣金汇总表 (4张物理分片) - 按用户维度汇总
CREATE TABLE t_commission_summary_0 (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64) NOT NULL COMMENT '用户openid',
    total_amount      INT         NOT NULL DEFAULT 0 COMMENT '总佣金额(分)',
    pending_amount    INT         NOT NULL DEFAULT 0 COMMENT '提现中佣金额(分)',
    withdrawn_amount  INT         NOT NULL DEFAULT 0 COMMENT '已提现佣金额(分)',
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金汇总分片表';

CREATE TABLE t_commission_summary_1 (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64) NOT NULL COMMENT '用户openid',
    total_amount      INT         NOT NULL DEFAULT 0 COMMENT '总佣金额(分)',
    pending_amount    INT         NOT NULL DEFAULT 0 COMMENT '提现中佣金额(分)',
    withdrawn_amount  INT         NOT NULL DEFAULT 0 COMMENT '已提现佣金额(分)',
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金汇总分片表';

CREATE TABLE t_commission_summary_2 (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64) NOT NULL COMMENT '用户openid',
    total_amount      INT         NOT NULL DEFAULT 0 COMMENT '总佣金额(分)',
    pending_amount    INT         NOT NULL DEFAULT 0 COMMENT '提现中佣金额(分)',
    withdrawn_amount  INT         NOT NULL DEFAULT 0 COMMENT '已提现佣金额(分)',
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金汇总分片表';

CREATE TABLE t_commission_summary_3 (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64) NOT NULL COMMENT '用户openid',
    total_amount      INT         NOT NULL DEFAULT 0 COMMENT '总佣金额(分)',
    pending_amount    INT         NOT NULL DEFAULT 0 COMMENT '提现中佣金额(分)',
    withdrawn_amount  INT         NOT NULL DEFAULT 0 COMMENT '已提现佣金额(分)',
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金汇总分片表';

-- 佣金明细表 (4张物理分片) - 记录每笔佣金明细
CREATE TABLE t_commission_detail_0 (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64)  NOT NULL COMMENT '获利者openid',
    from_openid       VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    order_no          VARCHAR(64)  NOT NULL,
    pay_amount        INT          NOT NULL COMMENT '订单支付金额(分)',
    commission_amount INT          NOT NULL COMMENT '佣金金额(分),30%最低2元',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    transfer_time     DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金明细分片表';

CREATE TABLE t_commission_detail_1 (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64)  NOT NULL COMMENT '获利者openid',
    from_openid       VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    order_no          VARCHAR(64)  NOT NULL,
    pay_amount        INT          NOT NULL COMMENT '订单支付金额(分)',
    commission_amount INT          NOT NULL COMMENT '佣金金额(分),30%最低2元',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    transfer_time     DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金明细分片表';

CREATE TABLE t_commission_detail_2 (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64)  NOT NULL COMMENT '获利者openid',
    from_openid       VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    order_no          VARCHAR(64)  NOT NULL,
    pay_amount        INT          NOT NULL COMMENT '订单支付金额(分)',
    commission_amount INT          NOT NULL COMMENT '佣金金额(分),30%最低2元',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    transfer_time     DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金明细分片表';

CREATE TABLE t_commission_detail_3 (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    openid            VARCHAR(64)  NOT NULL COMMENT '获利者openid',
    from_openid       VARCHAR(64)  NOT NULL COMMENT '支付者openid',
    order_no          VARCHAR(64)  NOT NULL,
    pay_amount        INT          NOT NULL COMMENT '订单支付金额(分)',
    commission_amount INT          NOT NULL COMMENT '佣金金额(分),30%最低2元',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    transfer_time     DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金明细分片表';

-- 提现表 (4张物理分片)
CREATE TABLE t_withdraw_0 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '用户openid',
    amount      INT          NOT NULL COMMENT '提现金额(分)',
    transferNo  VARCHAR(64)  COMMENT '微信商户订单号',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理 1=处理中 2=成功 3=失败 4=待审核',
    apply_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_openid (openid),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现分片表';

CREATE TABLE t_withdraw_1 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '用户openid',
    amount      INT          NOT NULL COMMENT '提现金额(分)',
    transferNo  VARCHAR(64)  COMMENT '微信商户订单号',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理 1=处理中 2=成功 3=失败 4=待审核',
    apply_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_openid (openid),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现分片表';

CREATE TABLE t_withdraw_2 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '用户openid',
    amount      INT          NOT NULL COMMENT '提现金额(分)',
    transferNo  VARCHAR(64)  COMMENT '微信商户订单号',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理 1=处理中 2=成功 3=失败 4=待审核',
    apply_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_openid (openid),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现分片表';

CREATE TABLE t_withdraw_3 (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    openid      VARCHAR(64)  NOT NULL COMMENT '用户openid',
    amount      INT          NOT NULL COMMENT '提现金额(分)',
    transferNo  VARCHAR(64)  COMMENT '微信商户订单号',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理 1=处理中 2=成功 3=失败 4=待审核',
    apply_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_openid (openid),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现分片表';