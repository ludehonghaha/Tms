package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点监控补充接口：网络质量和 Runtime 状态。
 *
 * TCP 探测实际在目标节点上执行，不是从面板服务器探测，因此结果能反映 VPS 本身
 * 到目标站点的真实链路。连续做多次单次 TCP connect 后，后端可同时给出延迟、
 * 丢包和抖动，而不需要依赖 ICMP 权限。
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/node")
public class NodeMonitoringController extends BaseController {

    private static final String DEFAULT_TARGET = "www.cloudflare.com";
    private static final int DEFAULT_PORT = 443;
    private static final int DEFAULT_COUNT = 5;
    private static final int MIN_COUNT = 3;
    private static final int MAX_COUNT = 10;

    @LogAnnotation
    @RequireRole
    @PostMapping("/quality")
    public R quality(@RequestBody Map<String, Object> params) {
        Long nodeId = longParam(params, "nodeId", null);
        if (nodeId == null) {
            return R.err("nodeId不能为空");
        }

        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return R.err("节点不存在");
        }
        if (node.getStatus() == null || node.getStatus() != 1) {
            return R.err("节点当前离线，无法检测网络质量");
        }

        String target = stringParam(params, "target", DEFAULT_TARGET).trim();
        int port = intParam(params, "port", DEFAULT_PORT);
        int count = intParam(params, "count", DEFAULT_COUNT);
        count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));

        if (target.isEmpty() || target.length() > 253) {
            return R.err("目标地址不合法");
        }
        if (port < 1 || port > 65535) {
            return R.err("端口必须在1-65535之间");
        }

        List<Double> samples = new ArrayList<>();
        int failed = 0;
        String lastError = null;

        for (int i = 0; i < count; i++) {
            JSONObject request = new JSONObject();
            request.put("ip", target);
            request.put("port", port);
            request.put("count", 1);
            request.put("timeout", 3000);

            GostDto response = WebSocketServer.send_msg(nodeId, request, "TcpPing");
            Double latency = extractLatency(response);
            if (latency != null && latency >= 0) {
                samples.add(latency);
            } else {
                failed++;
                if (response != null && response.getMsg() != null) {
                    lastError = response.getMsg();
                }
            }
        }

        double packetLoss = count == 0 ? 100.0 : failed * 100.0 / count;
        double average = average(samples);
        double min = samples.stream().mapToDouble(Double::doubleValue).min().orElse(-1.0);
        double max = samples.stream().mapToDouble(Double::doubleValue).max().orElse(-1.0);
        double jitter = jitter(samples);

        Map<String, Object> result = new HashMap<>();
        result.put("nodeId", nodeId);
        result.put("nodeName", node.getName());
        result.put("target", target);
        result.put("port", port);
        result.put("count", count);
        result.put("successCount", samples.size());
        result.put("averageTime", round2(average));
        result.put("minTime", round2(min));
        result.put("maxTime", round2(max));
        result.put("jitter", round2(jitter));
        result.put("packetLoss", round2(packetLoss));
        result.put("samples", samples);
        result.put("quality", qualityLevel(average, jitter, packetLoss));
        result.put("timestamp", System.currentTimeMillis());
        if (lastError != null) {
            result.put("lastError", lastError);
        }

        return R.ok(result);
    }

    /**
     * 返回数据库记录的连接状态与 Runtime 版本。节点握手/断线均由 WebSocketServer
     * 实时更新 status，因此这里可用于页面刷新后的状态恢复，不必等下一条监控广播。
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/check-status")
    public R checkStatus(@RequestBody(required = false) Map<String, Object> params) {
        Long nodeId = params == null ? null : longParam(params, "nodeId", null);
        if (nodeId != null) {
            Node node = nodeService.getById(nodeId);
            if (node == null) {
                return R.err("节点不存在");
            }
            return R.ok(statusMap(node));
        }

        List<Map<String, Object>> statuses = new ArrayList<>();
        for (Node node : nodeService.list()) {
            statuses.add(statusMap(node));
        }
        return R.ok(statuses);
    }

    private Map<String, Object> statusMap(Node node) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", node.getId());
        item.put("name", node.getName());
        item.put("online", node.getStatus() != null && node.getStatus() == 1);
        item.put("status", node.getStatus());
        item.put("version", node.getVersion());
        item.put("http", node.getHttp());
        item.put("tls", node.getTls());
        item.put("socks", node.getSocks());
        item.put("updatedTime", node.getUpdatedTime());
        return item;
    }

    private Double extractLatency(GostDto response) {
        if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
            return null;
        }
        try {
            JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
            if (!data.getBooleanValue("success")) {
                return null;
            }
            return data.getDouble("averageTime");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 平均相邻样本绝对差，作为 TCP RTT 抖动。 */
    private double jitter(List<Double> samples) {
        if (samples.size() < 2) {
            return samples.isEmpty() ? -1.0 : 0.0;
        }
        double total = 0;
        for (int i = 1; i < samples.size(); i++) {
            total += Math.abs(samples.get(i) - samples.get(i - 1));
        }
        return total / (samples.size() - 1);
    }

    private double average(List<Double> samples) {
        return samples.stream().mapToDouble(Double::doubleValue).average().orElse(-1.0);
    }

    private String qualityLevel(double latency, double jitter, double loss) {
        if (loss >= 20 || latency < 0) {
            return "poor";
        }
        if (loss >= 5 || latency >= 250 || jitter >= 80) {
            return "fair";
        }
        if (latency >= 120 || jitter >= 35) {
            return "good";
        }
        return "excellent";
    }

    private double round2(double value) {
        if (value < 0) {
            return value;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private String stringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Long longParam(Map<String, Object> params, String key, Long defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
