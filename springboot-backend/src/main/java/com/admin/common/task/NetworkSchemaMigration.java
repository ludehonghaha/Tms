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
 * ForwardX-style network orchestration schema.
 *
 * This migration is intentionally additive: it never alters existing TMS
 * node/tunnel/forward/inbound tables, so a failure here cannot change the
 * current proxy/subscription runtime.
 */
@Slf4j
@Component
@Order(2)
public class NetworkSchemaMigration implements ApplicationRunner {

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        List<String> ddls = List.of(
                """
                CREATE TABLE IF NOT EXISTS `node_group` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `name` VARCHAR(100) NOT NULL,
                  `role` VARCHAR(20) NOT NULL DEFAULT 'GENERIC',
                  `strategy` VARCHAR(20) NOT NULL DEFAULT 'PRIORITY',
                  `failover_enabled` TINYINT NOT NULL DEFAULT 1,
                  `status` TINYINT NOT NULL DEFAULT 1,
                  `created_time` BIGINT NOT NULL,
                  `updated_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_node_group_name` (`name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS `health_probe` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `name` VARCHAR(100) NOT NULL,
                  `source_node_id` BIGINT NOT NULL,
                  `target_node_id` BIGINT NULL,
                  `target_host` VARCHAR(255) NULL,
                  `target_port` INT NOT NULL,
                  `protocol` VARCHAR(20) NOT NULL DEFAULT 'TCP',
                  `count` INT NOT NULL DEFAULT 4,
                  `timeout_ms` INT NOT NULL DEFAULT 5000,
                  `interval_seconds` INT NOT NULL DEFAULT 60,
                  `enabled` TINYINT NOT NULL DEFAULT 1,
                  `last_success` TINYINT NULL,
                  `last_average_time` DOUBLE NULL,
                  `last_packet_loss` DOUBLE NULL,
                  `last_message` VARCHAR(500) NULL,
                  `last_run_time` BIGINT NULL,
                  `next_run_time` BIGINT NULL,
                  `created_time` BIGINT NOT NULL,
                  `updated_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  KEY `idx_health_probe_due` (`enabled`,`next_run_time`),
                  KEY `idx_health_probe_source` (`source_node_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS `health_probe_sample` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `probe_id` BIGINT NOT NULL,
                  `success` TINYINT NOT NULL,
                  `average_time` DOUBLE NULL,
                  `packet_loss` DOUBLE NULL,
                  `message` VARCHAR(500) NULL,
                  `created_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  KEY `idx_probe_sample_probe_time` (`probe_id`,`created_time`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS `node_group_member` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `group_id` BIGINT NOT NULL,
                  `node_id` BIGINT NOT NULL,
                  `priority` INT NOT NULL DEFAULT 100,
                  `weight` INT NOT NULL DEFAULT 1,
                  `health_probe_id` BIGINT NULL,
                  `status` TINYINT NOT NULL DEFAULT 1,
                  `created_time` BIGINT NOT NULL,
                  `updated_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_node_group_member` (`group_id`,`node_id`),
                  KEY `idx_node_group_member_node` (`node_id`),
                  KEY `idx_node_group_member_probe` (`health_probe_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS `network_chain` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `name` VARCHAR(100) NOT NULL,
                  `protocol` VARCHAR(30) NOT NULL DEFAULT 'AUTO',
                  `failover_enabled` TINYINT NOT NULL DEFAULT 1,
                  `remark` VARCHAR(500) NULL,
                  `status` TINYINT NOT NULL DEFAULT 1,
                  `created_time` BIGINT NOT NULL,
                  `updated_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_network_chain_name` (`name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS `network_chain_hop` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `chain_id` BIGINT NOT NULL,
                  `hop_order` INT NOT NULL,
                  `hop_type` VARCHAR(20) NOT NULL,
                  `node_id` BIGINT NULL,
                  `group_id` BIGINT NULL,
                  `transport` VARCHAR(30) NOT NULL DEFAULT 'AUTO',
                  `health_probe_id` BIGINT NULL,
                  `status` TINYINT NOT NULL DEFAULT 1,
                  `created_time` BIGINT NOT NULL,
                  `updated_time` BIGINT NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_network_chain_hop_order` (`chain_id`,`hop_order`),
                  KEY `idx_network_chain_hop_group` (`group_id`),
                  KEY `idx_network_chain_hop_node` (`node_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
        );

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            for (String ddl : ddls) {
                st.executeUpdate(ddl);
            }
            log.info("网络编排表结构检查完成");
        } catch (Exception e) {
            // Same safety policy as the existing SchemaMigration.
            log.warn("网络编排表结构迁移失败: {}", e.getMessage());
        }
    }
}
