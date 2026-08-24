package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

/**
 * Read-only runtime preflight for a compiled network chain.
 *
 * It reuses the existing Agent TcpPing command only. No Tunnel/Forward row is
 * created and no Agent runtime configuration is changed.
 */
@Service
public class NetworkPreflightService {

    @Resource
    private NetworkPlanCompiler compiler;

    public R preflight(Long chainId, Map<String, Object> body) {
        R dry = compiler.dryRun(chainId, body);
        if (dry.getCode() != 0) return dry;

        Map<String, Object> plan = map(dry.getData());
        if (plan == null) return R.err("dry-run没有返回计划");
        if (!boolValue(plan.get("executable"), false)) {
            return R.err("preflight需要 executable dry-run，请填写 targetHost/targetPort");
        }

        int portTimeoutMs = clamp(intValue(body.get("portCheckTimeoutMs"), 1200), 500, 5000);
        int targetTimeoutMs = clamp(intValue(body.get("targetCheckTimeoutMs"), 3000), 500, 10000);
        boolean checkEstimatedOutPorts = boolValue(body.get("checkEstimatedOutPorts"), true);
        boolean requireTargetReachable = boolValue(body.get("requireTargetReachable"), true);

        List<Map<String, Object>> checks = new ArrayList<>();
        Set<String> checkedPorts = new HashSet<>();

        List<Map<String, Object>> segments = listOfMaps(plan.get("segments"));
        for (Map<String, Object> segment : segments) {
            int index = intValue(segment.get("index"), 0);
            Long fromNodeId = nullableLong(segment.get("fromNodeId"));
            Integer plannedInPort = nullableInt(segment.get("plannedInPort"));
            if (fromNodeId == null || plannedInPort == null) {
                checks.add(check("PORT_FREE", index, fromNodeId, null, false,
                        "segment缺少 fromNodeId/plannedInPort"));
                continue;
            }
            String key = fromNodeId + ":" + plannedInPort;
            if (checkedPorts.add(key)) {
                checks.add(checkLocalPortFree(index, fromNodeId, plannedInPort, "plannedInPort", portTimeoutMs));
            }

            if (checkEstimatedOutPorts) {
                Long toNodeId = nullableLong(segment.get("toNodeId"));
                Integer estimatedOutPort = nullableInt(segment.get("estimatedOutPort"));
                if (toNodeId != null && estimatedOutPort != null) {
                    String outKey = toNodeId + ":" + estimatedOutPort;
                    if (checkedPorts.add(outKey)) {
                        checks.add(checkLocalPortFree(index, toNodeId, estimatedOutPort,
                                "estimatedOutPort", portTimeoutMs));
                    }
                }
            }
        }

        Map<String, Object> target = map(plan.get("target"));
        List<Map<String, Object>> hops = listOfMaps(plan.get("resolvedHops"));
        if (target != null && !hops.isEmpty()) {
            Map<String, Object> last = hops.get(hops.size() - 1);
            Long lastNodeId = nullableLong(last.get("nodeId"));
            String host = text(target.get("host"));
            Integer port = nullableInt(target.get("port"));
            if (lastNodeId == null || host.isBlank() || port == null) {
                checks.add(check("TARGET_REACHABLE", 0, lastNodeId, port, false,
                        "最终 Hop/target 信息不完整"));
            } else {
                checks.add(checkTarget(lastNodeId, host, port, targetTimeoutMs, requireTargetReachable));
            }
        }

        boolean ready = checks.stream().allMatch(c -> boolValue(c.get("passed"), false));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chainId);
        out.put("fingerprint", plan.get("fingerprint"));
        out.put("readyForApply", ready);
        out.put("checks", checks);
        out.put("entry", plan.get("entry"));
        out.put("target", plan.get("target"));
        out.put("safety", Map.of(
                "mutatesDatabase", false,
                "mutatesAgentRuntime", false,
                "usesAgentCommand", "TcpPing",
                "automaticFailoverEnabled", false
        ));
        out.put("generatedAt", System.currentTimeMillis());

        return ready ? R.ok(out) : errorWithData("preflight未通过，禁止进入 Apply", out);
    }

    private Map<String, Object> checkLocalPortFree(int segment, Long nodeId, int port,
                                                    String role, int timeoutMs) {
        ProbeResult probe = tcpPing(nodeId, "127.0.0.1", port, 1, timeoutMs);
        // For a local bind candidate, a successful connection means something is already listening.
        boolean free = probe.commandOk && !probe.connected;
        String message;
        if (!probe.commandOk) {
            message = "Agent自检失败: " + probe.message;
        } else if (probe.connected) {
            message = "端口已存在 listener，拒绝使用";
        } else {
            message = "未检测到 TCP listener，可作为候选端口";
        }
        Map<String, Object> out = check("PORT_FREE", segment, nodeId, port, free, message);
        out.put("role", role);
        out.put("agentCommandOk", probe.commandOk);
        out.put("tcpConnected", probe.connected);
        out.put("averageTime", probe.averageTime);
        out.put("packetLoss", probe.packetLoss);
        return out;
    }

    private Map<String, Object> checkTarget(Long nodeId, String host, int port,
                                            int timeoutMs, boolean required) {
        ProbeResult probe = tcpPing(nodeId, host, port, 2, timeoutMs);
        boolean reachable = probe.commandOk && probe.connected;
        boolean passed = reachable || !required;
        Map<String, Object> out = check("TARGET_REACHABLE", 0, nodeId, port, passed,
                reachable ? "最终出口可访问 target" : "最终出口无法访问 target: " + probe.message);
        out.put("host", host);
        out.put("required", required);
        out.put("reachable", reachable);
        out.put("agentCommandOk", probe.commandOk);
        out.put("averageTime", probe.averageTime);
        out.put("packetLoss", probe.packetLoss);
        return out;
    }

    private ProbeResult tcpPing(Long nodeId, String host, int port, int count, int timeoutMs) {
        if (nodeId == null) return ProbeResult.commandError("nodeId为空");
        JSONObject req = new JSONObject();
        req.put("ip", host);
        req.put("port", port);
        req.put("count", count);
        req.put("timeout", timeoutMs);

        try {
            GostDto result = WebSocketServer.send_msg(nodeId, req, "TcpPing");
            if (result == null) return ProbeResult.commandError("Agent无响应");
            if (!"OK".equalsIgnoreCase(text(result.getMsg()))) {
                return ProbeResult.commandError(firstNonBlank(text(result.getMsg()), "Agent命令失败"));
            }
            JSONObject data = toJson(result.getData());
            if (data == null) return ProbeResult.commandError("TcpPing响应缺少data");
            boolean success = data.getBooleanValue("success");
            double averageTime = data.containsKey("averageTime") ? data.getDoubleValue("averageTime") : (success ? 0 : -1);
            double packetLoss = data.containsKey("packetLoss") ? data.getDoubleValue("packetLoss") : (success ? 0 : 100);
            String message = success ? "OK" : firstNonBlank(data.getString("errorMessage"), "TCP连接失败");
            return new ProbeResult(true, success, averageTime, packetLoss, message);
        } catch (Exception e) {
            return ProbeResult.commandError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static Map<String, Object> check(String type, int segment, Long nodeId,
                                             Integer port, boolean passed, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("segment", segment);
        out.put("nodeId", nodeId);
        out.put("port", port);
        out.put("passed", passed);
        out.put("message", message);
        return out;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) out.add((Map<String, Object>) item);
        }
        return out;
    }

    private static R errorWithData(String message, Object data) {
        R r = R.err(message);
        r.setData(data);
        return r;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
        Integer n = nullableInt(value);
        return n == null ? fallback : n;
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        if (value instanceof Boolean b) return b;
        String s = text(value);
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ProbeResult(boolean commandOk, boolean connected, double averageTime,
                               double packetLoss, String message) {
        static ProbeResult commandError(String message) {
            return new ProbeResult(false, false, -1, 100, message);
        }
    }
}
