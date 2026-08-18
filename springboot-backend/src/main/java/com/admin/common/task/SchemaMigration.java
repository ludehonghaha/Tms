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
 * 启动时的表结构自动迁移。
 *
 * gost.sql 只在【新装】时执行一次,已经装好的面板 tms update 只换镜像、不动表结构。
 * 所以往实体里加字段必须配一次 ALTER,否则老用户一查就 Unknown column,面板直接崩。
 *
 * 这里只做「列不存在就加」这一种最安全的迁移:先查 information_schema 确认列缺失,
 * 再执行 ADD COLUMN。幂等、可重复执行,失败也只记日志不影响启动 ——
 * 迁移挂了顶多是新功能不可用,不能连带把整个面板拖死。
 */
@Slf4j
@Component
@Order(1)
public class SchemaMigration implements ApplicationRunner {

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        // 转发机的「连接域名」:填了就用它生成节点链接,车友看到的是域名而不是车主的 IP
        addColumnIfMissing("node", "domain",
                "ALTER TABLE `node` ADD COLUMN `domain` VARCHAR(255) NULL COMMENT '连接域名(可选,留空用 server_ip)'");
        addColumnIfMissing("user", "master_sub_token",
                "ALTER TABLE `user` ADD COLUMN `master_sub_token` VARCHAR(100) NULL COMMENT '用户总订阅 token'");
        addColumnIfMissing("inbound_line", "used_flow",
                "ALTER TABLE `inbound_line` ADD COLUMN `used_flow` BIGINT NOT NULL DEFAULT 0 COMMENT '已移除协议归档流量(字节)'");
    }

    /** 列不存在才执行 ddl;任何异常都吞掉(只记日志),不能因为迁移失败导致面板起不来 */
    private void addColumnIfMissing(String table, String column, String ddl) {
        try (Connection conn = dataSource.getConnection()) {
            if (columnExists(conn, table, column)) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(ddl);
                log.info("表结构迁移: {}.{} 已添加", table, column);
            }
        } catch (Exception e) {
            // 并发启动时另一个实例可能刚好加完(1060 Duplicate column),这属于正常情况
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Duplicate column") || msg.contains("1060")) {
                log.debug("表结构迁移: {}.{} 已存在,跳过", table, column);
            } else {
                log.warn("表结构迁移失败 {}.{}: {}", table, column, msg);
            }
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }
}
