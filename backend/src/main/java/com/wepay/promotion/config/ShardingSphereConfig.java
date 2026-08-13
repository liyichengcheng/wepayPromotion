package com.wepay.promotion.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.config.algorithm.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.*;

/**
 * ShardingSphere DataSource 编程式配置
 *
 * 不使用 YAML 配置文件, 直接通过 Java API 创建 ShardingSphereDataSource,
 * 避免 ShardingSphereYamlConstructor 的 SnakeYAML 版本兼容问题。
 *
 * 单库分表: 1个数据源(ds0) + 4张物理分片表(_0~_3)
 * 分片键: openid, 算法: HASH_MOD(4)
 */
@Configuration
public class ShardingSphereConfig {

    @Bean
    @Primary
    public DataSource dataSource() throws Exception {
        // 1. 数据源
        Map<String, DataSource> dataSourceMap = new HashMap<>();
        HikariDataSource ds0 = new HikariDataSource();
        ds0.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds0.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/wepay_promotion?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        ds0.setUsername("wepay");
        ds0.setPassword("wepay");
        dataSourceMap.put("ds0", ds0);

        // 2. 分片算法
        Properties algorithmProps = new Properties();
        algorithmProps.setProperty("sharding-count", "4");
        AlgorithmConfiguration algorithmConfig = new AlgorithmConfiguration("HASH_MOD", algorithmProps);

        // 3. 分片规则
        ShardingRuleConfiguration shardingRuleConfig = new ShardingRuleConfiguration();
        shardingRuleConfig.getShardingAlgorithms().put("openid-table", algorithmConfig);

        // 4. 分片表
        String[] tables = {"t_user", "t_pay_order", "t_commission_detail", "t_commission_summary", "t_withdraw"};
        for (String table : tables) {
            ShardingTableRuleConfiguration tableRule = new ShardingTableRuleConfiguration(
                    table, "ds0." + table + "_${0..3}");
            tableRule.setTableShardingStrategy(
                    new StandardShardingStrategyConfiguration("openid", "openid-table"));
            shardingRuleConfig.getTables().add(tableRule);
        }

        // 5. 广播表
        shardingRuleConfig.getBroadcastTables().add("t_article");

        // 6. 全局属性
        Properties props = new Properties();
        props.setProperty("sql-show", "true");

        return ShardingSphereDataSourceFactory.createDataSource(
                dataSourceMap, Collections.singleton(shardingRuleConfig), props);
    }
}
