package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Compile a Network Chain into an execution preview for the existing
 * TMS Tunnel + Forward runtime.
 *
 * This class is deliberately READ-ONLY. It never inserts/updates/deletes
 * tunnel, forward or agent runtime state. The output is a stable plan that a
 * later apply phase can validate and execute.
 */
@Service
public class NetworkPlanCompiler {

    @Resource
    private JdbcTemplate jdbc;

    @Resource
    private NodeService nodeService;

    /**
     * Build a read-only execution plan.
     *
     * Body options:
     * - targetHost: final target host (optional for topology-only preview)
     * - targetPort: final target port
     * - entryPort: requested public port on the first node; null = candidate auto allocation
     * - strictHealth: default true; reject offline/unhealthy selected hops
     * - flow: tunnel billing mode, default 2
     * - trafficRatio: default 1.0
     */
    public R dryRun(Long chainId, Map<String, Object> body) {
        try {
            Map<String, Object> chain = one("SELECT * FROM network_chain WHERE id=?", chainId);
            if (chain == null) return R.err("链路不存在");
            if (intValue(chain.get("status"), 0) != 1) return R.err("链路已禁用");

            List<Map<String, Object>> hops = jdbc.queryForList(
                    "SELECT * FROM network_chain_hop WHERE chain_id=? AND status=1 ORDER BY hop_order ASC", chainId);
            if (hops.size() < 2) return R.err("链路至少需要2个启用 Hop");

            boolean strictHealth = boolValue(body.get("strictHealth"), true);
            String targetHost = text(body.get("targetHost"));
            int targetPort = intValue(body.get("targetPort"), 0);
            Integer requestedEntryPort = nullableInt(body.get("entryPort"));
            int flow = intValue(body.get("flow"), 2);
            double trafficRatio = doubleValue(body.get("trafficRatio"), 1.0d);

            if ((!targetHost.isBlank() && (targetPort < 1 || targetPort > 65535)) ||
                    (targetHost.isBlank() && targetPort != 0)) {
                return R.err("targetHost/targetPort必须同时有效；仅预览拓扑时两者都留空");
            }
            if (requestedEntryPort != null && (requestedEntryPort < 1 || requestedEntryPort > 65535)) {
                return R.err("entryPort必须在1-65535范围内");
            }

            List<String> warnings = new ArrayList<>();
            List<ResolvedHop> resolved = new ArrayList<>();
            for (Map<String, Object> hop : hops) {
                ResolvedHop selected = resolveHop(hop, strictHealth, warnings);
                if (selected == null) return R.err("Hop " + hop.get("hop_order") + " 无法解析到可用节点");
                resolved.add(selected);
            }

            for (int i = 1; i < resolved.size(); i++) {
                if (Objects.equals(resolved.get(i - 1).node.getId(), resolved.get(i).node.getId())) {
                    return R.err("相邻 Hop 解析到了同一节点 " + resolved.get(i).node.getName() + "，无法创建隧道转发");
                }
            }

            PortPlanner ports = new PortPlanner();
            List<SegmentPlan> segments = new ArrayList<>();
            String chainProtocol = normalizeProtocol(chain.get("protocol"));

            for (int i = 0; i < resolved.size() - 1; i++) {
                ResolvedHop from = resolved.get(i);
                ResolvedHop to = resolved.get(i + 1);
                String segmentProtocol = resolveSegmentProtocol(from.transport, chainProtocol);

                Integer inPort = ports.allocate(from.node, i == 0 ? requestedEntryPort : null);
                Integer estimatedOutPort = ports.allocate(to.node, null);

                Map<String, Object> existingTunnel = findReusableTunnel(
                        from.node.getId(), to.node.getId(), segmentProtocol);

                SegmentPlan segment = new SegmentPlan();
                segment.index = i + 1;
                segment.from = from;
                segment.to = to;
                segment.protocol = segmentProtocol;
                segment.inPort = inPort;
                segment.estimatedOutPort = estimatedOutPort;
                segment.existingTunnel = existingTunnel;
                segments.add(segment);
            }

            boolean executable = !targetHost.isBlank() && targetPort >= 1 && targetPort <= 65535;
            List<Map<String, Object>> actions = buildActions(chainId, text(chain.get("name")), segments,
                    targetHost, targetPort, flow, trafficRatio, executable);

            Node firstNode = resolved.get(0).node;
            String entryAddress = firstNonBlank(firstNode.getDomain(), firstNode.getIp(), firstNode.getServerIp());

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("planVersion", 2);
            plan.put("mode", "DRY_RUN");
            plan.put("chainId", chainId);
            plan.put("chainName", chain.get("name"));
            plan.put("chainUpdatedTime", chain.get("updated_time"));
            plan.put("executable", executable);
            plan.put("strictHealth", strictHealth);
            plan.put("entry", Map.of(
                    "nodeId", firstNode.getId(),
                    "nodeName", safe(firstNode.getName()),
                    "address", safe(entryAddress),
                    "plannedPort", segments.get(0).inPort
            ));
            if (executable) {
                plan.put("target", Map.of("host", targetHost, "port", targetPort, "address", hostPort(targetHost, targetPort)));
            } else {
                plan.put("target", null);
                warnings.add("未填写 targetHost/targetPort：当前仅为拓扑 dry-run，不可执行 Apply");
            }
            plan.put("resolvedHops", resolved.stream().map(ResolvedHop::asMap).toList());
            plan.put("segments", segments.stream().map(SegmentPlan::asMap).toList());
            plan.put("actions", actions);
            plan.put("warnings", warnings);
            plan.put("safety", Map.of(
                    "mutatesDatabase", false,
                    "mutatesAgentRuntime", false,
                    "changesExistingTunnelForward", false,
                    "applyEnabled", false
            ));
            plan.put("generatedAt", System.currentTimeMillis());
            plan.put("fingerprint", fingerprint(plan));

            return R.ok(plan);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        } catch (Exception e) {
            return R.err("链路 dry-run 编译失败: " + e.getMessage());
        }
    }

    private ResolvedHop resolveHop(Map<String, Object> hop, boolean strictHealth, List<String> warnings) {
        int order = intValue(hop.get("hop_order"), 0);
        String type = upper(hop.get("hop_type"), "NODE");
        String transport = upper(hop.get("transport"), "AUTO");
        Long hopProbeId = nullableLong(hop.get("health_probe_id"));

        if ("NODE".equals(type)) {
            Long nodeId = nullableLong(hop.get("node_id"));
            Node node = nodeId == null ? null : nodeService.getById(nodeId);
            if (node == null) throw new IllegalArgumentException("Hop " + order + " 指定的节点不存在");

            boolean online = Objects.equals(node.getStatus(), 1);
            Boolean probeHealthy = probeHealthy(hopProbeId);
            boolean healthy = online && !Boolean.FALSE.equals(probeHealthy);
            if (!healthy) {
                String why = !online ? "Agent离线" : "Hop健康检查失败";
                if (strictHealth) throw new IllegalArgumentException("Hop " + order + " 节点 " + node.getName() + " 不健康: " + why);
                warnings.add("Hop " + order + " 节点 " + node.getName() + " 当前不健康(" + why + ")，因 strictHealth=false 仍保留在预览中");
            }
            if (probeHealthy == null && hopProbeId != null) {
                warnings.add("Hop " + order + " 的健康检查尚未产生结果");
            }
            return ResolvedHop.direct(order, transport, hopProbeId, node, healthy, online);
        }

        if (!"GROUP".equals(type)) {
            throw new IllegalArgumentException("Hop " + order + " 类型不支持: " + type);
        }

        Long groupId = nullableLong(hop.get("group_id"));
        if (groupId == null) throw new IllegalArgumentException("Hop " + order + " 缺少 group_id");

        Map<String, Object> group = one("SELECT * FROM node_group WHERE id=?", groupId);
        if (group == null || intValue(group.get("status"), 0) != 1) {
            throw new IllegalArgumentException("Hop " + order + " 节点组不存在或已禁用");
        }

        List<Map<String, Object>> members = jdbc.queryForList("""
                SELECT m.*, n.name AS node_name, n.server_ip, n.ip AS node_ip, n.domain, n.status AS node_status,
                       p.last_success, p.last_average_time, p.last_packet_loss, p.last_message
                FROM node_group_member m
                JOIN node n ON n.id=m.node_id
                LEFT JOIN health_probe p ON p.id=m.health_probe_id
                WHERE m.group_id=? AND m.status=1
                ORDER BY m.priority ASC, m.id ASC
                """, groupId);
        if (members.isEmpty()) throw new IllegalArgumentException("Hop " + order + " 节点组没有启用成员");

        List<Map<String, Object>> healthy = members.stream().filter(this::memberHealthy).toList();
        List<Map<String, Object>> pool = healthy;
        if (pool.isEmpty()) {
            if (strictHealth) throw new IllegalArgumentException("Hop " + order + " 节点组成员全部离线或健康检查失败");
            pool = members;
            warnings.add("Hop " + order + " 节点组没有健康成员，因 strictHealth=false 使用启用成员生成预览");
        }

        String strategy = upper(group.get("strategy"), "PRIORITY");
        Map<String, Object> chosen = choosePreviewMember(pool, strategy, warnings, order);
        Long nodeId = nullableLong(chosen.get("node_id"));
        Node node = nodeId == null ? null : nodeService.getById(nodeId);
        if (node == null) throw new IllegalArgumentException("Hop " + order + " 选中的组成员节点不存在");

        List<Map<String, Object>> candidates = pool.stream().map(this::candidateSummary).toList();
        boolean online = Objects.equals(node.getStatus(), 1);
        boolean selectedHealthy = memberHealthy(chosen);
        return ResolvedHop.group(order, transport, hopProbeId, node, selectedHealthy, online,
                groupId, text(group.get("name")), strategy, candidates);
    }

    private Map<String, Object> choosePreviewMember(List<Map<String, Object>> pool, String strategy,
                                                     List<String> warnings, int hopOrder) {
        if (pool.isEmpty()) throw new IllegalArgumentException("节点组没有候选成员");
        return switch (strategy) {
            case "LOWEST_RTT" -> pool.stream().min(Comparator
                    .comparingDouble((Map<String, Object> r) -> doubleValue(r.get("last_average_time"), Double.MAX_VALUE))
                    .thenComparingInt(r -> intValue(r.get("priority"), 100)))
                    .orElse(pool.get(0));
            case "WEIGHTED" -> {
                warnings.add("Hop " + hopOrder + " 使用 WEIGHTED：dry-run 为可重复预览，固定选择当前权重最高成员；正式 Apply 会保存本次选中快照");
                yield pool.stream().max(Comparator
                        .comparingInt((Map<String, Object> r) -> intValue(r.get("weight"), 1))
                        .thenComparingInt(r -> -intValue(r.get("priority"), 100)))
                        .orElse(pool.get(0));
            }
            case "ROUND_ROBIN" -> {
                warnings.add("Hop " + hopOrder + " 使用 ROUND_ROBIN：dry-run 不推进轮询游标，固定预览第一个健康成员");
                yield pool.get(0);
            }
            default -> pool.get(0);
        };
    }

    private List<Map<String, Object>> buildActions(Long chainId, String chainName, List<SegmentPlan> segments,
                                                    String targetHost, int targetPort, int flow,
                                                    double trafficRatio, boolean executable) {
        List<Map<String, Object>> actions = new ArrayList<>();
        int seq = 1;

        // Phase 1: make sure every pair has a Tunnel row. This is still preview only.
        for (SegmentPlan segment : segments) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("seq", seq++);
            action.put("phase", "TUNNEL");
            if (segment.existingTunnel != null) {
                action.put("operation", "REUSE_TUNNEL");
                action.put("existingTunnelId", segment.existingTunnel.get("id"));
                action.put("existingTunnelName", segment.existingTunnel.get("name"));
            } else {
                action.put("operation", "CREATE_TUNNEL");
                action.put("spec", tunnelSpec(chainId, chainName, segment, flow, trafficRatio));
            }
            action.put("segment", segment.index);
            actions.add(action);
        }

        // Phase 2: create downstream forwards first. Each upstream segment exits on the
        // next node and connects to 127.0.0.1:<next segment inPort>.
        for (int i = segments.size() - 1; i >= 0; i--) {
            SegmentPlan segment = segments.get(i);
            String remoteAddr;
            if (i == segments.size() - 1) {
                remoteAddr = executable ? hostPort(targetHost, targetPort) : "<TARGET_REQUIRED>";
            } else {
                remoteAddr = "127.0.0.1:" + segments.get(i + 1).inPort;
            }

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", "net-chain-" + chainId + "-seg-" + segment.index);
            spec.put("tunnelRef", segment.existingTunnel == null
                    ? "$segment." + segment.index + ".tunnelId"
                    : segment.existingTunnel.get("id"));
            spec.put("remoteAddr", remoteAddr);
            spec.put("inPort", segment.inPort);
            spec.put("strategy", "fifo");
            spec.put("interfaceName", null);
            spec.put("estimatedOutPort", segment.estimatedOutPort);
            spec.put("estimatedOutPortEnforced", false);

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("seq", seq++);
            action.put("phase", "FORWARD");
            action.put("operation", executable ? "CREATE_FORWARD" : "PREVIEW_FORWARD");
            action.put("segment", segment.index);
            action.put("dependsOnDownstreamSegment", i == segments.size() - 1 ? null : segments.get(i + 1).index);
            action.put("spec", spec);
            actions.add(action);
        }
        return actions;
    }

    private Map<String, Object> tunnelSpec(Long chainId, String chainName, SegmentPlan segment,
                                           int flow, double trafficRatio) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "net-" + chainId + "-" + segment.index + "-" + safe(chainName));
        spec.put("inNodeId", segment.from.node.getId());
        spec.put("outNodeId", segment.to.node.getId());
        spec.put("type", 2);
        spec.put("flow", flow);
        spec.put("trafficRatio", trafficRatio);
        spec.put("protocol", segment.protocol);
        spec.put("tcpListenAddr", "0.0.0.0");
        spec.put("udpListenAddr", "0.0.0.0");
        spec.put("interfaceName", null);
        return spec;
    }

    private Map<String, Object> findReusableTunnel(Long inNodeId, Long outNodeId, String protocol) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,name,protocol,in_node_id,out_node_id,status
                FROM tunnel
                WHERE in_node_id=? AND out_node_id=? AND type=2 AND protocol=? AND status=1
                ORDER BY id ASC LIMIT 1
                """, inNodeId, outNodeId, protocol);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean memberHealthy(Map<String, Object> row) {
        if (intValue(row.get("node_status"), 0) != 1) return false;
        Object probe = row.get("last_success");
        return probe == null || intValue(probe, 0) == 1;
    }

    private Boolean probeHealthy(Long probeId) {
        if (probeId == null) return null;
        Map<String, Object> row = one("SELECT last_success FROM health_probe WHERE id=?", probeId);
        if (row == null || row.get("last_success") == null) return null;
        return intValue(row.get("last_success"), 0) == 1;
    }

    private Map<String, Object> candidateSummary(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", row.get("node_id"));
        out.put("nodeName", row.get("node_name"));
        out.put("priority", row.get("priority"));
        out.put("weight", row.get("weight"));
        out.put("agentOnline", intValue(row.get("node_status"), 0) == 1);
        out.put("healthy", memberHealthy(row));
        out.put("rttMs", row.get("last_average_time"));
        out.put("packetLoss", row.get("last_packet_loss"));
        return out;
    }

    private String resolveSegmentProtocol(String hopTransport, String chainProtocol) {
        if (hopTransport != null && !hopTransport.isBlank() && !"AUTO".equalsIgnoreCase(hopTransport)) {
            return hopTransport.toLowerCase(Locale.ROOT);
        }
        if (chainProtocol != null && !chainProtocol.isBlank() && !"AUTO".equalsIgnoreCase(chainProtocol)) {
            return chainProtocol.toLowerCase(Locale.ROOT);
        }
        return "tls";
    }

    private String normalizeProtocol(Object value) {
        String protocol = upper(value, "AUTO");
        return protocol.isBlank() ? "AUTO" : protocol;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Read existing database reservations; OS-level conflicts are rechecked during Apply. */
    private class PortPlanner {
        private final Map<Long, Set<Integer>> used = new HashMap<>();

        Integer allocate(Node node, Integer requested) {
            if (node.getPortSta() == null || node.getPortEnd() == null || node.getPortSta() > node.getPortEnd()) {
                throw new IllegalArgumentException("节点 " + node.getName() + " 的端口范围无效");
            }
            Set<Integer> nodeUsed = used.computeIfAbsent(node.getId(), id -> loadUsedPorts(id));

            if (requested != null) {
                if (requested < node.getPortSta() || requested > node.getPortEnd()) {
                    throw new IllegalArgumentException("端口 " + requested + " 不在节点 " + node.getName() + " 的允许范围 " + node.getPortSta() + "-" + node.getPortEnd());
                }
                if (nodeUsed.contains(requested)) {
                    throw new IllegalArgumentException("端口 " + requested + " 已在节点 " + node.getName() + " 的 TMS 配置中占用");
                }
                nodeUsed.add(requested);
                return requested;
            }

            for (int p = node.getPortSta(); p <= node.getPortEnd(); p++) {
                if (!nodeUsed.contains(p)) {
                    nodeUsed.add(p);
                    return p;
                }
            }
            throw new IllegalArgumentException("节点 " + node.getName() + " 的可管理端口范围已无空闲候选端口");
        }

        private Set<Integer> loadUsedPorts(Long nodeId) {
            Set<Integer> result = new HashSet<>();
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT f.in_port AS port FROM forward f JOIN tunnel t ON t.id=f.tunnel_id WHERE t.in_node_id=? AND f.in_port IS NOT NULL
                    UNION ALL
                    SELECT f.out_port AS port FROM forward f JOIN tunnel t ON t.id=f.tunnel_id WHERE t.out_node_id=? AND f.out_port IS NOT NULL
                    UNION ALL
                    SELECT i.listen_port AS port FROM inbound i WHERE i.node_id=? AND i.listen_port IS NOT NULL
                    """, nodeId, nodeId, nodeId);
            for (Map<String, Object> row : rows) {
                Integer port = nullableInt(row.get("port"));
                if (port != null) result.add(port);
            }
            return result;
        }
    }

    private static class SegmentPlan {
        int index;
        ResolvedHop from;
        ResolvedHop to;
        String protocol;
        Integer inPort;
        Integer estimatedOutPort;
        Map<String, Object> existingTunnel;

        Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("index", index);
            out.put("fromNodeId", from.node.getId());
            out.put("fromNodeName", from.node.getName());
            out.put("toNodeId", to.node.getId());
            out.put("toNodeName", to.node.getName());
            out.put("protocol", protocol);
            out.put("plannedInPort", inPort);
            out.put("estimatedOutPort", estimatedOutPort);
            out.put("estimatedOutPortEnforced", false);
            out.put("tunnelMode", existingTunnel == null ? "CREATE" : "REUSE");
            out.put("existingTunnelId", existingTunnel == null ? null : existingTunnel.get("id"));
            return out;
        }
    }

    private static class ResolvedHop {
        int hopOrder;
        String hopType;
        String transport;
        Long healthProbeId;
        Node node;
        boolean healthy;
        boolean agentOnline;
        Long groupId;
        String groupName;
        String groupStrategy;
        List<Map<String, Object>> candidates = List.of();

        static ResolvedHop direct(int order, String transport, Long probeId, Node node,
                                  boolean healthy, boolean online) {
            ResolvedHop h = new ResolvedHop();
            h.hopOrder = order;
            h.hopType = "NODE";
            h.transport = transport;
            h.healthProbeId = probeId;
            h.node = node;
            h.healthy = healthy;
            h.agentOnline = online;
            return h;
        }

        static ResolvedHop group(int order, String transport, Long probeId, Node node,
                                 boolean healthy, boolean online, Long groupId,
                                 String groupName, String groupStrategy,
                                 List<Map<String, Object>> candidates) {
            ResolvedHop h = direct(order, transport, probeId, node, healthy, online);
            h.hopType = "GROUP";
            h.groupId = groupId;
            h.groupName = groupName;
            h.groupStrategy = groupStrategy;
            h.candidates = candidates;
            return h;
        }

        Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hopOrder", hopOrder);
            out.put("hopType", hopType);
            out.put("transport", transport);
            out.put("healthProbeId", healthProbeId);
            out.put("nodeId", node.getId());
            out.put("nodeName", node.getName());
            out.put("nodeIp", node.getIp());
            out.put("serverIp", node.getServerIp());
            out.put("domain", node.getDomain());
            out.put("agentOnline", agentOnline);
            out.put("healthy", healthy);
            out.put("groupId", groupId);
            out.put("groupName", groupName);
            out.put("groupStrategy", groupStrategy);
            out.put("candidates", candidates);
            return out;
        }
    }

    private static String fingerprint(Map<String, Object> plan) {
        try {
            // Exclude generatedAt/fingerprint so the same topology/config has a stable id.
            Map<String, Object> stable = new LinkedHashMap<>(plan);
            stable.remove("generatedAt");
            stable.remove("fingerprint");
            byte[] bytes = com.alibaba.fastjson.JSON.toJSONString(stable).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(plan.hashCode());
        }
    }

    private static String hostPort(String host, int port) {
        String h = text(host);
        if (h.startsWith("[") && h.endsWith("]")) return h + ":" + port;
        if (h.contains(":")) return "[" + h + "]:" + port;
        return h + ":" + port;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) return value.trim();
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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

    private static Integer nullableInt(Object value) {
        if (value == null || text(value).isBlank()) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.valueOf(text(value));
    }

    private static int intValue(Object value, int fallback) {
        Integer v = nullableInt(value);
        return v == null ? fallback : v;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(text(value));
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        if (value instanceof Boolean b) return b;
        String s = text(value);
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }
}
