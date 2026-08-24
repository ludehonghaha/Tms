package com.admin.common.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 监控模块的轻量、幂等索引迁移。
 *
 * statistics_flow 从 48 小时扩展到 31 天后，按 user_id + created_time 查询是高频路径；
 * 老库没有这个索引时会随着用户数增长逐渐变成全表扫描。
 */
@Slf4j
@Component
@Order(2)
public class MonitoringSchemaMigration implements ApplicationRunner {

    private static final String INDEX_NAME = "idx_statistics_flow_user_time";

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            if (indexExists(conn, "statistics_flow", INDEX_NAME)) {
                return;
            }
            try (Statement statement = conn.createStatement()) {
                statement.executeUpdate(
                        "CREATE INDEX `" + INDEX_NAME + "` ON `statistics_flow` (`user_id`, `created_time`)"
                );
                log.info("表结构迁移: statistics_flow.{} 已添加", INDEX_NAME);
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            // 多实例同时启动时，另一实例可能已经创建完成。
            if (message.contains("Duplicate key name") || message.contains("1061")) {
                log.debug("表结构迁移: statistics_flow.{} 已存在,跳过", INDEX_NAME);
            } else {
                // 与原 SchemaMigration 一致：索引失败只能影响性能，不能阻止面板启动。
                log.warn("监控索引迁移失败: {}", message);
            }
        }
    }

    private boolean indexExists(Connection conn, String table, String indexName) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
