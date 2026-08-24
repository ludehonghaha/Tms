package com.admin.common.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * Additive deployment/audit tables for Network Chain Apply + rollback.
 * No existing TMS table is altered.
 */
@Slf4j
@Component
@Order(3)
public class NetworkDeploymentSchemaMigration implements ApplicationRunner {

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        List<String> ddls = List.of(
                """
                CREATE TABLE IF NOT EXISTS `network_deployment` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `chain_id` BIGINT NOT NULL,
                  `fingerprint` VARCHAR(64) NOT NULL,
                  `target_host` VARCHAR(255) NULL,
                  `target_port` INT NULL,
                  `entry_node_id` BIGINT NULL,
                  `entry_port` INT NULL,
                  `state` VARCHAR(30) NOT NULL,
                  `plan_json` LONGTEXT NULL,
                  `error_message` VARCHAR(1000) NULL,
                  `created_by_user_id` BIGINT NULL,
                  `created_by_user_name` VARCHAR(100) NULL,
                  `created_time` BIGINT NOT NULL,
                  `updated_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  KEY `idx_network_deployment_chain` (`chain_id`,`created_time`),
                  KEY `idx_network_deployment_fingerprint` (`fingerprint`),
                  KEY `idx_network_deployment_state` (`state`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS `network_deployment_resource` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `deployment_id` BIGINT NOT NULL,
                  `segment_index` INT NULL,
                  `resource_type` VARCHAR(20) NOT NULL,
                  `resource_id` BIGINT NOT NULL,
                  `owned` TINYINT NOT NULL DEFAULT 1,
                  `resource_name` VARCHAR(120) NULL,
                  `created_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  KEY `idx_network_deployment_resource` (`deployment_id`,`resource_type`),
                  KEY `idx_network_resource_lookup` (`resource_type`,`resource_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
        );

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            for (String ddl : ddls) {
                st.executeUpdate(ddl);
            }
            log.info("网络编排 deployment 表结构检查完成");
        } catch (Exception e) {
            log.warn("网络编排 deployment 表结构迁移失败: {}", e.getMessage());
        }
    }
}
