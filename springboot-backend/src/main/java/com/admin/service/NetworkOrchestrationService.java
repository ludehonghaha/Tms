package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Additive network-orchestration control plane.
 *
 * The model is isolated from existing Tunnel/Forward/Inbound resources. Runtime
 * compilation and Apply are handled by NetworkPlanCompiler/NetworkPlanApplyService.
 */
@Slf4j
@Service
public class NetworkOrchestrationService {

    @Resource
    private JdbcTemplate jdbc;

    @Resource
    private NodeService nodeService;

    private final ConcurrentHashMap<Long, AtomicInteger> roundRobinCursor = new ConcurrentHashMap<>();

    public R listGroups() {
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT * FROM node_group ORDER BY id ASC");
        for (Map<String, Object> group : groups) {
            Long groupId = longValue(group.get("id"));
            group.put("members", jdbc.queryForList("""
                    SELECT m.*, n.name AS node_name, n.server_ip, n.ip AS node_ip, n.status AS node_status,
                           p.last_success, p.last_average_time, p.last_packet_loss, p.last_message, p.last_run_time
                    FROM node_group_member m
                    LEFT JOIN node n ON n.id=m.node_id
                    LEFT JOIN health_probe p ON p.id=m.health_probe_id
                    WHERE m.group_id=? ORDER BY m.priority ASC, m.id ASC
                    """, groupId));
        }
        return R.ok(groups);
    }

    public R saveGroup(Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name.isBlank()) return R.err("组名称不能为空");
        String role = upper(body.get("role"), "GENERIC");
        String strategy = upper(body.get("strategy"), "PRIORITY");
        if (!Set.of("INGRESS", "EGRESS", "TRANSIT", "GENERIC").contains(role)) {
            return R.err("role 仅支持 INGRESS/EGRESS/TRANSIT/GENERIC");
        }
        if (!Set.of("PRIORITY", "ROUND_ROBIN", "WEIGHTED", "LOWEST_RTT").contains(strategy)) {
            return R.err("strategy 不支持");
        }
        int failover = intValue(body.get("failoverEnabled"), 1);
        int status = intValue(body.get("status"), 1);
        long now = System.currentTimeMillis();
        Long id = nullableLong(body.get("id"));
        if (id == null) {
            jdbc.update("INSERT INTO node_group(name,role,strategy,failover_enabled,status,created_time,updated_time) VALUES(?,?,?,?,?,?,?)",
                    name, role, strategy, failover, status, now, now);
        } else {
            jdbc.update("UPDATE node_group SET name=?,role=?,strategy=?,failover_enabled=?,status=?,updated_time=? WHERE id=?",
                    name, role, strategy, failover, status, now, id);
        }
        return R.ok("保存成功");
    }

    @Transactional
    public R deleteGroup(Long id) {
        long used = jdbc.queryForObject("SELECT COUNT(*) FROM network_chain_hop WHERE group_id=?", Long.class, id);
        if (used > 0) return R.err("该节点组仍被链路引用，请先移除对应 Hop");
        jdbc.update("DELETE FROM node_group_member WHERE group_id=?", id);
        jdbc.update("DELETE FROM node_group WHERE id=?", id);
        roundRobinCursor.remove(id);
        return R.ok("删除成功");
    }

    public R saveGroupMember(Map<String, Object> body) {
        Long groupId = nullableLong(body.get("groupId"));
        Long nodeId = nullableLong(body.get("nodeId"));
        if (groupId == null || nodeId == null) return R.err("groupId/nodeId不能为空");
        if (!exists("SELECT COUNT(*) FROM node_group WHERE id=?", groupId)) return R.err("节点组不存在");
        if (nodeService.getById(nodeId) == null) return R.err("节点不存在");
        int priority = intValue(body.get("priority"), 100);
        int weight = Math.max(1, intValue(body.get("weight"), 1));
        Long probeId = nullableLong(body.get("healthProbeId"));
        if (probeId != null && !exists("SELECT COUNT(*) FROM health_probe WHERE id=?", probeId)) {
            return R.err("健康检查不存在");
        }
        int status = intValue(body.get("status"), 1);
        long now = System.currentTimeMillis();
        Long id = nullableLong(body.get("id"));
        if (id == null) {
            jdbc.update("""
                    INSERT INTO node_group_member(group_id,node_id,priority,weight,health_probe_id,status,created_time,updated_time)
                    VALUES(?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE priority=VALUES(priority),weight=VALUES(weight),health_probe_id=VALUES(health_probe_id),status=VALUES(status),updated_time=VALUES(updated_time)
                    """, groupId, nodeId, priority, weight, probeId, status, now, now);
        } else {
            jdbc.update("UPDATE node_group_member SET group_id=?,node_id=?,priority=?,weight=?,health_probe_id=?,status=?,updated_time=? WHERE id=?",
                    groupId, nodeId, priority, weight, probeId, status, now, id);
        }
        return R.ok("保存成功");
    }

    public R deleteGroupMember(Long id) {
        jdbc.update("DELETE FROM node_group_member WHERE id=?", id);
        return R.ok("删除成功");
    }

    public R selectGroupMember(Long groupId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.*, g.strategy, g.failover_enabled,
                       n.name AS node_name, n.server_ip, n.ip AS node_ip, n.status AS node_status,
                       p.last_success, p.last_average_time, p.last_packet_loss, p.last_message
                FROM node_group_member m
                JOIN node_group g ON g.id=m.group_id
                JOIN node n ON n.id=m.node_id
                LEFT JOIN health_probe p ON p.id=m.health_probe_id
                WHERE m.group_id=? AND m.status=1 AND g.status=1
                ORDER BY m.priority ASC, m.id ASC
                """, groupId);
        if (rows.isEmpty()) return R.err("节点组没有可用成员");

        List<Map<String, Object>> healthy = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (intValue(row.get("node_status"), 0) != 1) continue;
            Object probeState = row.get("last_success");
            if (probeState != null && intValue(probeState, 0) != 1) continue;
            healthy.add(row);
        }
        if (healthy.isEmpty()) return R.err("节点组成员全部离线或健康检查失败");

        String strategy = upper(healthy.get(0).get("strategy"), "PRIORITY");
        Map<String, Object> selected;
        switch (strategy) {
            case "LOWEST_RTT" -> selected = healthy.stream().min(Comparator
                    .comparingDouble((Map<String, Object> r) -> doubleValue(r.get("last_average_time"), Double.MAX_VALUE))
                    .thenComparingInt(r -> intValue(r.get("priority"), 100))).orElse(healthy.get(0));
            case "ROUND_ROBIN" -> {
                AtomicInteger cursor = roundRobinCursor.computeIfAbsent(groupId, k -> new AtomicInteger());
                selected = healthy.get(Math.floorMod(cursor.getAndIncrement(), healthy.size()));
            }
            case "WEIGHTED" -> selected = weighted(healthy);
            default -> selected = healthy.get(0);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupId", groupId);
        result.put("strategy", strategy);
        result.put("selected", selected);
        result.put("candidates", healthy);
        return R.ok(result);
    }

    public R listChains() {
        List<Map<String, Object>> chains = jdbc.queryForList("SELECT * FROM network_chain ORDER BY id ASC");
        for (Map<String, Object> chain : chains) {
            chain.put("hops", jdbc.queryForList("""
                    SELECT h.*, n.name AS node_name, g.name AS group_name, g.role AS group_role, g.strategy AS group_strategy,
                           p.last_success, p.last_average_time, p.last_packet_loss, p.last_message
                    FROM network_chain_hop h
                    LEFT JOIN node n ON n.id=h.node_id
                    LEFT JOIN node_group g ON g.id=h.group_id
                    LEFT JOIN health_probe p ON p.id=h.health_probe_id
                    WHERE h.chain_id=? ORDER BY h.hop_order ASC
                    """, longValue(chain.get("id"))));
        }
        return R.ok(chains);
    }

    /**
     * Save a chain atomically from the caller's perspective.
     *
     * All hop references are normalized and validated before the first INSERT,
     * UPDATE or DELETE. This is important because returning R.err does not mark a
     * Spring transaction rollback-only; validation must therefore be completed
     * before any mutation occurs.
     */
    @Transactional
    public R saveChain(Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name.isBlank()) return R.err("链路名称不能为空");
        String protocol = upper(body.get("protocol"), "AUTO");
        int failover = intValue(body.get("failoverEnabled"), 1);
        String remark = text(body.get("remark"));
        int status = intValue(body.get("status"), 1);
        Long id = nullableLong(body.get("id"));

        ChainHopValidation hopValidation = validateChainHops(body.get("hops"));
        if (hopValidation.error != null) return R.err(hopValidation.error);
        List<Map<String, Object>> normalizedHops = hopValidation.hops;

        if (id != null && !exists("SELECT COUNT(*) FROM network_chain WHERE id=?", id)) {
            return R.err("链路不存在");
        }

        long now = System.currentTimeMillis();
        if (id == null) {
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO network_chain(name,protocol,failover_enabled,remark,status,created_time,updated_time) VALUES(?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, protocol);
                ps.setInt(3, failover);
                ps.setString(4, remark);
                ps.setInt(5, status);
                ps.setLong(6, now);
                ps.setLong(7, now);
                return ps;
            }, holder);
            Number key = holder.getKey();
            if (key == null) throw new IllegalStateException("network_chain主键获取失败");
            id = key.longValue();
        } else {
            jdbc.update("UPDATE network_chain SET name=?,protocol=?,failover_enabled=?,remark=?,status=?,updated_time=? WHERE id=?",
                    name, protocol, failover, remark, status, now, id);
            jdbc.update("DELETE FROM network_chain_hop WHERE chain_id=?", id);
        }

        for (Map<String, Object> hop : normalizedHops) {
            jdbc.update("""
                    INSERT INTO network_chain_hop(chain_id,hop_order,hop_type,node_id,group_id,transport,health_probe_id,status,created_time,updated_time)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, id,
                    hop.get("hopOrder"), hop.get("hopType"), hop.get("nodeId"), hop.get("groupId"),
                    hop.get("transport"), hop.get("healthProbeId"), hop.get("status"), now, now);
        }
        return R.ok(Map.of("id", id, "message", "保存成功"));
    }

    private ChainHopValidation validateChainHops(Object hopsObj) {
        if (hopsObj == null) return new ChainHopValidation(List.of(), null);
        if (!(hopsObj instanceof Collection<?> hops)) {
            return new ChainHopValidation(List.of(), "hops必须为数组");
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<Integer> orders = new HashSet<>();
        for (Object raw : hops) {
            if (!(raw instanceof Map<?, ?> m)) {
                return new ChainHopValidation(List.of(), "每个Hop必须为对象");
            }
            Map<String, Object> hop = new LinkedHashMap<>();
            m.forEach((k, v) -> hop.put(String.valueOf(k), v));

            int order;
            Long nodeId;
            Long groupId;
            Long probeId;
            int hopStatus;
            try {
                order = intValue(hop.get("hopOrder"), 0);
                nodeId = nullableLong(hop.get("nodeId"));
                groupId = nullableLong(hop.get("groupId"));
                probeId = nullableLong(hop.get("healthProbeId"));
                hopStatus = intValue(hop.get("status"), 1);
            } catch (Exception e) {
                return new ChainHopValidation(List.of(), "Hop数字字段格式不合法");
            }

            if (order <= 0 || !orders.add(order)) {
                return new ChainHopValidation(List.of(), "hopOrder必须为正数且不能重复");
            }
            String hopType = upper(hop.get("hopType"), "NODE");
            if (!Set.of("NODE", "GROUP").contains(hopType)) {
                return new ChainHopValidation(List.of(), "hopType仅支持NODE/GROUP");
            }
            if (hopStatus != 0 && hopStatus != 1) {
                return new ChainHopValidation(List.of(), "Hop status仅支持0/1");
            }

            if ("NODE".equals(hopType)) {
                if (nodeId == null) return new ChainHopValidation(List.of(), "NODE Hop缺少nodeId");
                if (nodeService.getById(nodeId) == null) {
                    return new ChainHopValidation(List.of(), "NODE Hop引用的节点不存在: " + nodeId);
                }
                groupId = null;
            } else {
                if (groupId == null) return new ChainHopValidation(List.of(), "GROUP Hop缺少groupId");
                if (!exists("SELECT COUNT(*) FROM node_group WHERE id=?", groupId)) {
                    return new ChainHopValidation(List.of(), "GROUP Hop引用的节点组不存在: " + groupId);
                }
                nodeId = null;
            }

            if (probeId != null && !exists("SELECT COUNT(*) FROM health_probe WHERE id=?", probeId)) {
                return new ChainHopValidation(List.of(), "Hop引用的健康检查不存在: " + probeId);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hopOrder", order);
            out.put("hopType", hopType);
            out.put("nodeId", nodeId);
            out.put("groupId", groupId);
            out.put("transport", upper(hop.get("transport"), "AUTO"));
            out.put("healthProbeId", probeId);
            out.put("status", hopStatus);
            normalized.add(out);
        }

        normalized.sort(Comparator.comparingInt(h -> intValue(h.get("hopOrder"), 0)));
        return new ChainHopValidation(normalized, null);
    }

    @Transactional
    public R deleteChain(Long id) {
        jdbc.update("DELETE FROM network_chain_hop WHERE chain_id=?", id);
        jdbc.update("DELETE FROM network_chain WHERE id=?", id);
        return R.ok("删除成功");
    }

    public R listProbes() {
        return R.ok(jdbc.queryForList("""
                SELECT p.*, s.name AS source_node_name, t.name AS target_node_name
                FROM health_probe p
                LEFT JOIN node s ON s.id=p.source_node_id
                LEFT JOIN node t ON t.id=p.target_node_id
                ORDER BY p.id ASC
                """));
    }

    public R saveProbe(Map<String, Object> body) {
        String name = text(body.get("name"));
        Long sourceNodeId = nullableLong(body.get("sourceNodeId"));
        Long targetNodeId = nullableLong(body.get("targetNodeId"));
        String targetHost = text(body.get("targetHost"));
        int targetPort = intValue(body.get("targetPort"), 0);
        if (name.isBlank() || sourceNodeId == null || targetPort < 1 || targetPort > 65535) {
            return R.err("name/sourceNodeId/targetPort不合法");
        }
        if (targetNodeId == null && targetHost.isBlank()) return R.err("targetNodeId和targetHost至少填写一个");
        if (nodeService.getById(sourceNodeId) == null) return R.err("source节点不存在");
        if (targetNodeId != null && nodeService.getById(targetNodeId) == null) return R.err("target节点不存在");

        int count = Math.max(1, Math.min(10, intValue(body.get("count"), 4)));
        int timeoutMs = Math.max(500, Math.min(30000, intValue(body.get("timeoutMs"), 5000)));
        int interval = Math.max(10, intValue(body.get("intervalSeconds"), 60));
        int enabled = intValue(body.get("enabled"), 1);
        long now = System.currentTimeMillis();
        Long id = nullableLong(body.get("id"));
        if (id == null) {
            jdbc.update("""
                    INSERT INTO health_probe(name,source_node_id,target_node_id,target_host,target_port,protocol,count,timeout_ms,interval_seconds,enabled,next_run_time,created_time,updated_time)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, name, sourceNodeId, targetNodeId, targetHost.isBlank() ? null : targetHost, targetPort, "TCP", count, timeoutMs, interval, enabled, now, now, now);
        } else {
            jdbc.update("""
                    UPDATE health_probe SET name=?,source_node_id=?,target_node_id=?,target_host=?,target_port=?,protocol='TCP',count=?,timeout_ms=?,interval_seconds=?,enabled=?,next_run_time=?,updated_time=? WHERE id=?
                    """, name, sourceNodeId, targetNodeId, targetHost.isBlank() ? null : targetHost, targetPort, count, timeoutMs, interval, enabled, now, now, id);
        }
        return R.ok("保存成功");
    }

    public R deleteProbe(Long id) {
        long used = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM node_group_member WHERE health_probe_id=?) + (SELECT COUNT(*) FROM network_chain_hop WHERE health_probe_id=?)", Long.class, id, id);
        if (used > 0) return R.err("该健康检查仍被节点组或链路引用");
        jdbc.update("DELETE FROM health_probe_sample WHERE probe_id=?", id);
        jdbc.update("DELETE FROM health_probe WHERE id=?", id);
        return R.ok("删除成功");
    }

    public R runProbe(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM health_probe WHERE id=?", id);
        if (rows.isEmpty()) return R.err("健康检查不存在");
        return R.ok(executeProbe(rows.get(0)));
    }

    public R listProbeSamples(Long probeId, int limit) {
        limit = Math.max(1, Math.min(1000, limit));
        return R.ok(jdbc.queryForList("SELECT * FROM health_probe_sample WHERE probe_id=? ORDER BY created_time DESC LIMIT " + limit, probeId));
    }

    public R topology() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", jdbc.queryForList("SELECT id,name,ip,server_ip,domain,version,port_sta,port_end,status FROM node ORDER BY id ASC"));
        result.put("groups", listGroups().getData());
        result.put("chains", listChains().getData());
        result.put("probes", listProbes().getData());
        result.put("generatedAt", System.currentTimeMillis());
        return R.ok(result);
    }

    /** Probe scheduler. Existing traffic continues even if this task fails. */
    @Scheduled(fixedDelay = 10000L)
    public void runDueProbes() {
        try {
            long now = System.currentTimeMillis();
            List<Map<String, Object>> due = jdbc.queryForList(
                    "SELECT * FROM health_probe WHERE enabled=1 AND (next_run_time IS NULL OR next_run_time<=?) ORDER BY id ASC LIMIT 20", now);
            for (Map<String, Object> probe : due) {
                try {
                    executeProbe(probe);
                } catch (Exception e) {
                    log.debug("健康检查执行失败 id={}: {}", probe.get("id"), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("健康检查调度暂不可用: {}", e.getMessage());
        }
    }

    private Map<String, Object> executeProbe(Map<String, Object> probe) {
        Long id = longValue(probe.get("id"));
        Long sourceNodeId = longValue(probe.get("source_node_id"));
        Long targetNodeId = nullableLong(probe.get("target_node_id"));
        String targetHost = text(probe.get("target_host"));
        int targetPort = intValue(probe.get("target_port"), 0);
        int count = intValue(probe.get("count"), 4);
        int timeoutMs = intValue(probe.get("timeout_ms"), 5000);
        int interval = intValue(probe.get("interval_seconds"), 60);
        long now = System.currentTimeMillis();

        if (targetNodeId != null) {
            Node target = nodeService.getById(targetNodeId);
            if (target != null) targetHost = target.getServerIp();
        }

        boolean success = false;
        double averageTime = -1;
        double packetLoss = 100;
        String message;

        if (targetHost.isBlank()) {
            message = "目标地址为空";
        } else {
            JSONObject req = new JSONObject();
            req.put("ip", targetHost);
            req.put("port", targetPort);
            req.put("count", count);
            req.put("timeout", timeoutMs);
            GostDto result = WebSocketServer.send_msg(sourceNodeId, req, "TcpPing");
            if (result != null && "OK".equalsIgnoreCase(result.getMsg())) {
                JSONObject data = toJson(result.getData());
                if (data == null) {
                    success = true;
                    averageTime = 0;
                    packetLoss = 0;
                    message = "OK";
                } else {
                    success = data.getBooleanValue("success");
                    averageTime = data.containsKey("averageTime") ? data.getDoubleValue("averageTime") : (success ? 0 : -1);
                    packetLoss = data.containsKey("packetLoss") ? data.getDoubleValue("packetLoss") : (success ? 0 : 100);
                    message = success ? "OK" : Optional.ofNullable(data.getString("errorMessage")).orElse("探测失败");
                }
            } else {
                message = result == null ? "Agent无响应" : text(result.getMsg());
            }
        }

        long nextRun = now + interval * 1000L;
        jdbc.update("""
                UPDATE health_probe SET last_success=?,last_average_time=?,last_packet_loss=?,last_message=?,last_run_time=?,next_run_time=?,updated_time=? WHERE id=?
                """, success ? 1 : 0, averageTime, packetLoss, truncate(message, 500), now, nextRun, now, id);
        jdbc.update("INSERT INTO health_probe_sample(probe_id,success,average_time,packet_loss,message,created_time) VALUES(?,?,?,?,?,?)",
                id, success ? 1 : 0, averageTime, packetLoss, truncate(message, 500), now);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probeId", id);
        out.put("sourceNodeId", sourceNodeId);
        out.put("targetHost", targetHost);
        out.put("targetPort", targetPort);
        out.put("success", success);
        out.put("averageTime", averageTime);
        out.put("packetLoss", packetLoss);
        out.put("message", message);
        out.put("timestamp", now);
        return out;
    }

    private Map<String, Object> weighted(List<Map<String, Object>> candidates) {
        int total = candidates.stream().mapToInt(r -> Math.max(1, intValue(r.get("weight"), 1))).sum();
        int hit = ThreadLocalRandom.current().nextInt(total);
        for (Map<String, Object> row : candidates) {
            hit -= Math.max(1, intValue(row.get("weight"), 1));
            if (hit < 0) return row;
        }
        return candidates.get(0);
    }

    private boolean exists(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count != null && count > 0;
    }

    private static JSONObject toJson(Object data) {
        if (data == null) return null;
        if (data instanceof JSONObject json) return json;
        try {
            return JSON.parseObject(JSON.toJSONString(data));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String upper(Object value, String fallback) {
        String s = text(value);
        return s.isBlank() ? fallback : s.toUpperCase(Locale.ROOT);
    }

    private static Long nullableLong(Object value) {
        if (value == null || text(value).isBlank()) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(text(value));
    }

    private static Long longValue(Object value) {
        Long v = nullableLong(value);
        return v == null ? 0L : v;
    }

    private static int intValue(Object value, int fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(text(value));
    }

    private static double doubleValue(Object value, double fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(text(value));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }

    private record ChainHopValidation(List<Map<String, Object>> hops, String error) {}
}
