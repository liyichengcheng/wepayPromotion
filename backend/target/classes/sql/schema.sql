-- =====================================================
-- 微信支付推广后端数据库脚本（分库分表版）
-- 主库: wepay_promotion (存放文章等不分片的表 + 所有物理分片表)
-- 分片规则: HASH_MOD(user_id, 4) => 表名后缀 = 哈希值
--
-- 说明: application.yml 中 ds0~ds3 均指向 wepay_promotion 库,
-- ShardingSphere 按 user_id HASH_MOD(4) 计算库路由(ds0..3)和表路由(_0..3),
-- 由于所有 ds 指向同一库, 实际为单库分表(4 张物理表), 分库路由等效于别名。
-- =====================================================

-- 1. 创建主库
CREATE DATABASE IF NOT EXISTS wepay_promotion DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE wepay_promotion;

-- 文章表 (不分片, broadcast-table)
CREATE TABLE IF NOT EXISTS t_article (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    article_id  BIGINT       NOT NULL,
    title       VARCHAR(255) NOT NULL,
    base_price  INT          NOT NULL DEFAULT 6 COMMENT '基础价格(分)',
    max_price   INT          NOT NULL DEFAULT 2000 COMMENT '最高价格(分)',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_id (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章表(主库)';

-- 初始化文章数据
INSERT INTO t_article (article_id, title, base_price, max_price)
VALUES (10001, '学渣逆袭：高考倒状元到百万年薪', 600, 2000)
ON DUPLICATE KEY UPDATE title = VALUES(title);


-- 2. 创建物理分片表 (在 wepay_promotion 库中, 每种表 4 张: _0 ~ _3)
DELIMITER $$

DROP PROCEDURE IF EXISTS create_shard_tables $$
CREATE PROCEDURE create_shard_tables()
BEGIN
    DECLARE i INT DEFAULT 0;

    WHILE i < 4 DO
        -- 用户表 (分片)
        SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS t_user_', i, ' (
            id          BIGINT       NOT NULL AUTO_INCREMENT,
            user_id     VARCHAR(64)  NOT NULL COMMENT ''业务用户ID(分片键)'',
            openid      VARCHAR(64)  NOT NULL COMMENT ''微信openid'',
            union_id    VARCHAR(64)  DEFAULT NULL,
            create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_user_id (user_id),
            UNIQUE KEY uk_openid (openid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''用户分片表''');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        -- 支付订单表 (分片)
        SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS t_pay_order_', i, ' (
            id               BIGINT       NOT NULL AUTO_INCREMENT,
            order_no         VARCHAR(64)  NOT NULL COMMENT ''商户订单号'',
            user_id          VARCHAR(64)  NOT NULL COMMENT ''支付用户ID(分片键)'',
            openid           VARCHAR(64)  NOT NULL COMMENT ''支付者openid'',
            article_id       BIGINT       NOT NULL,
            pay_price        INT          NOT NULL COMMENT ''支付金额(分)'',
            parent_share_uid VARCHAR(64)  DEFAULT NULL COMMENT ''分享者(上级)user_id'',
            status           TINYINT      NOT NULL DEFAULT 0 COMMENT ''0=未支付 1=已支付'',
            prepay_id        VARCHAR(128) DEFAULT NULL,
            transaction_id   VARCHAR(64)  DEFAULT NULL,
            pay_time         DATETIME     DEFAULT NULL,
            create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_order_no (order_no),
            KEY idx_user_id (user_id),
            KEY idx_parent (parent_share_uid),
            KEY idx_article (article_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''支付订单分片表''');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        -- 佣金汇总表 (分片) - 按用户维度汇总
        SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS t_commission_summary_', i, ' (
            id                BIGINT      NOT NULL AUTO_INCREMENT,
            user_id           VARCHAR(64) NOT NULL COMMENT ''用户ID(分片键)'',
            openid            VARCHAR(64) NOT NULL COMMENT ''支付者openid'',
            total_amount      INT         NOT NULL DEFAULT 0 COMMENT ''总佣金额(分)'',
            pending_amount    INT         NOT NULL DEFAULT 0 COMMENT ''提现中佣金额(分)'',
            withdrawn_amount  INT         NOT NULL DEFAULT 0 COMMENT ''已提现佣金额(分)'',
            create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_user_id (user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''佣金汇总分片表''');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        -- 佣金明细表 (分片) - 记录每笔佣金明细
        SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS t_commission_detail_', i, ' (
            id                BIGINT       NOT NULL AUTO_INCREMENT,
            user_id           VARCHAR(64)  NOT NULL COMMENT ''获利者user_id(分片键)'',
            openid            VARCHAR(64)  NOT NULL COMMENT ''获利者openid'',
            from_user_id      VARCHAR(64)  NOT NULL COMMENT ''支付者user_id'',
            order_no          VARCHAR(64)  NOT NULL,
            pay_amount        INT          NOT NULL COMMENT ''订单支付金额(分)'',
            commission_amount INT          NOT NULL COMMENT ''佣金金额(分),30%最低2元'',
            create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            transfer_time     DATETIME     DEFAULT NULL,
            PRIMARY KEY (id),
            UNIQUE KEY uk_order_no (order_no),
            KEY idx_user_id (user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''佣金明细分片表''');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        -- 提现表 (分片)
        SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS t_withdraw_', i, ' (
            id          BIGINT       NOT NULL AUTO_INCREMENT,
            user_id     VARCHAR(64)  NOT NULL COMMENT ''用户ID(分片键)'',
            openid      VARCHAR(64)  NOT NULL COMMENT ''支付者openid'',
            amount      INT          NOT NULL COMMENT ''提现金额(分)'',
            status      TINYINT      NOT NULL DEFAULT 0 COMMENT ''0=待处理 1=处理中 2=成功 3=失败'',
            apply_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_user_id (user_id),
            KEY idx_status (status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''提现分片表''');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        SET i = i + 1;
    END WHILE;
END $$

DELIMITER ;

-- 执行存储过程创建分片表
CALL create_shard_tables();
DROP PROCEDURE IF EXISTS create_shard_tables;
