package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/**
 * Guarded Apply/rollback layer for NetworkPlanCompiler.
 *
 * Safety gates:
 * 1. tms.network.apply-enabled defaults to false.
 * 2. caller must send confirm=APPLY.
 * 3. caller must send the exact current dry-run fingerprint.
 * 4. dry-run is recomputed immediately before mutation.
 * 5. created resources are tracked and rolled back in reverse order on failure.
 *
 * Automatic failover is intentionally NOT implemented here.
 */
@Service
public class NetworkPlanApplyService {

    @Value("${tms.network.apply-enabled:false}")
    private boolean applyEnabled;

    @Resource
    private JdbcTemplate jdbc;

    @Resource
    private NetworkPlanCompiler compiler;

    @Resource
    private TunnelService tunnelService;

    @Resource
    private ForwardService forwardService;

    public R listDeployments() {
        List<Map<String, Object>> deployments = jdbc.queryForList("""
                SELECT d.*,
                       (SELECT COUNT(*) FROM network_deployment_resource r WHERE r.deployment_id=d.id) AS resource_count
                FROM network_deployment d
                ORDER BY d.id DESC LIMIT 100
                """);
        for (Map<String, Object> d : deployments) {
            d.put("resources", jdbc.queryForList(
                    "SELECT * FROM network_deployment_resource WHERE deployment_id=? ORDER BY id ASC",
                    longValue(d.get("id"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applyEnabled", applyEnabled);
        out.put("deployments", deployments);
        return R.ok(out);
    }

    public synchronized R apply(Map<String, Object> body) {
        if (!applyEnabled) {
            return R.err("Network Apply 当前默认关闭。确认 dry-run/回滚验证完成后，显式设置 TMS_NETWORK_APPLY_ENABLED=true 才可启用");
        }
        if (!"APPLY".equalsIgnoreCase(text(body.get("confirm")))) {
            return R.err("缺少二次确认：confirm 必须等于 APPLY");
        }

        Long chainId = nullableLong(body.get("chainId"));
        String requestedFingerprint = text(body.get("fingerprint"));
        if (chainId == null || requestedFingerprint.isBlank()) {
            return R.err("chainId/fingerprint不能为空");
        }

        R dryRun = compiler.dryRun(chainId, body);
        if (dryRun.getCode() != 0) return dryRun;
        Map<String, Object> plan = map(dryRun.getData());
        if (plan == null) return R.err("dry-run没有返回可用计划");
        if (!boolValue(plan.get("executable"), false)) {
            return R.err("该计划没有最终 target，不能 Apply");
        }

        String currentFingerprint = text(plan.get("fingerprint"));
        if (!requestedFingerprint.equals(currentFingerprint)) {
            R err = R.err("计划已变化，拒绝 Apply；请重新 dry-run 并确认新的 fingerprint");
            err.setData(Map.of("requestedFingerprint", requestedFingerprint, "currentFingerprint", currentFingerprint));
            return err;
        }

        List<Map<String, Object>> active = jdbc.queryForList(
                "SELECT id,state,entry_node_id,entry_port FROM network_deployment WHERE fingerprint=? AND state='ACTIVE' ORDER BY id DESC LIMIT 1",
                currentFingerprint);
        if (!active.isEmpty()) {
            R err = R.err("相同 fingerprint 已存在 ACTIVE deployment，拒绝重复创建");
            err.setData(active.get(0));
            return err;
        }

        Integer userId = JwtUtil.getUserIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        long deploymentId;
        try {
            deploymentId = createDeployment(chainId, currentFingerprint, plan, userId, userName);
        } catch (Exception e) {
            return R.err("创建 deployment 记录失败: " + e.getMessage());
        }

        List<Long> createdForwardIds = new ArrayList<>();
        List<Long> createdTunnelIds = new ArrayList<>();
        Map<Integer, Long> segmentTunnelIds = new HashMap<>();

        try {
            List<Map<String, Object>> actions = listOfMaps(plan.get("actions"));

            // Phase 1: create/reuse all pairwise tunnel rows.
            for (Map<String, Object> action : actions) {
                if (!"TUNNEL".equals(text(action.get("phase")))) continue;
                int segment = intValue(action.get("segment"), 0);
                String operation = text(action.get("operation"));

                if ("REUSE_TUNNEL".equals(operation)) {
                    Long tunnelId = nullableLong(action.get("existingTunnelId"));
                    if (tunnelId == null || tunnelService.getById(tunnelId) == null) {
                        throw new ApplyException("Segment " + segment + " 计划复用的 Tunnel 已不存在");
                    }
                    segmentTunnelIds.put(segment, tunnelId);
                    trackResource(deploymentId, segment, "TUNNEL", tunnelId, false,
                            text(action.get("existingTunnelName")));
                    continue;
                }

                if (!"CREATE_TUNNEL".equals(operation)) {
                    throw new ApplyException("未知 Tunnel action: " + operation);
                }

                Map<String, Object> spec = map(action.get("spec"));
                if (spec == null) throw new ApplyException("Segment " + segment + " 缺少 Tunnel spec");
                TunnelDto dto = tunnelDto(spec);
                dto.setName(shortName(dto.getName(), 100));

                R created = tunnelService.createTunnel(dto);
                if (created.getCode() != 0) {
                    throw new ApplyException("Segment " + segment + " 创建 Tunnel 失败: " + created.getMsg());
                }

                Map<String, Object> row = one("SELECT id,name FROM tunnel WHERE name=? ORDER BY id DESC LIMIT 1", dto.getName());
                if (row == null) {
                    throw new ApplyException("Segment " + segment + " Tunnel 已创建但无法回查ID");
                }
                Long tunnelId = longValue(row.get("id"));
                segmentTunnelIds.put(segment, tunnelId);
                createdTunnelIds.add(tunnelId);
                trackResource(deploymentId, segment, "TUNNEL", tunnelId, true, dto.getName());
            }

            // Phase 2: compiler already orders Forward actions downstream -> upstream.
            for (Map<String, Object> action : actions) {
                if (!"FORWARD".equals(text(action.get("phase")))) continue;
                if (!"CREATE_FORWARD".equals(text(action.get("operation")))) {
                    throw new ApplyException("计划包含不可执行的 Forward action，请重新生成 executable dry-run");
                }
                int segment = intValue(action.get("segment"), 0);
                Map<String, Object> spec = map(action.get("spec"));
                if (spec == null) throw new ApplyException("Segment " + segment + " 缺少 Forward spec");

                Long tunnelId = resolveTunnelRef(spec.get("tunnelRef"), segmentTunnelIds);
                if (tunnelId == null) throw new ApplyException("Segment " + segment + " 无法解析 tunnelRef");

                ForwardDto dto = new ForwardDto();
                dto.setName(shortName(text(spec.get("name")), 100));
                dto.setTunnelId(tunnelId.intValue());
                dto.setRemoteAddr(text(spec.get("remoteAddr")));
                dto.setInPort(nullableInt(spec.get("inPort")));
                dto.setStrategy(firstNonBlank(text(spec.get("strategy")), "fifo"));
                dto.setInterfaceName(blankToNull(text(spec.get("interfaceName"))));

                R created = forwardService.createForwardForUser(dto, userId, userName);
                if (created.getCode() != 0) {
                    throw new ApplyException("Segment " + segment + " 创建 Forward 失败: " + created.getMsg());
                }

                Long forwardId = extractForwardId(created.getData(), dto, userId);
                if (forwardId == null) {
                    throw new ApplyException("Segment " + segment + " Forward 已创建但无法确认ID");
                }
                createdForwardIds.add(forwardId);
                trackResource(deploymentId, segment, "FORWARD", forwardId, true, dto.getName());
            }

            jdbc.update("UPDATE network_deployment SET state='ACTIVE',updated_time=? WHERE id=?",
                    System.currentTimeMillis(), deploymentId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deploymentId", deploymentId);
            result.put("state", "ACTIVE");
            result.put("fingerprint", currentFingerprint);
            result.put("entry", plan.get("entry"));
            result.put("target", plan.get("target"));
            result.put("createdTunnelIds", createdTunnelIds);
            result.put("createdForwardIds", createdForwardIds);
            result.put("automaticFailoverEnabled", false);
            return R.ok(result);
        } catch (Exception e) {
            List<String> rollbackErrors = rollbackCreated(createdForwardIds, createdTunnelIds);
            String state = rollbackErrors.isEmpty() ? "FAILED_ROLLED_BACK" : "ROLLBACK_FAILED";
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!rollbackErrors.isEmpty()) message += " | rollback: " + String.join("; ", rollbackErrors);
            jdbc.update("UPDATE network_deployment SET state=?,error_message=?,updated_time=? WHERE id=?",
                    state, truncate(message, 1000), System.currentTimeMillis(), deploymentId);

            R err = R.err("Network Apply 失败，状态=" + state + ": " + message);
            err.setData(Map.of("deploymentId", deploymentId, "state", state, "rollbackErrors", rollbackErrors));
            return err;
        }
    }

    public synchronized R rollback(Map<String, Object> body) {
        if (!"ROLLBACK".equalsIgnoreCase(text(body.get("confirm")))) {
            return R.err("缺少二次确认：confirm 必须等于 ROLLBACK");
        }
        Long deploymentId = nullableLong(body.get("deploymentId"));
        if (deploymentId == null) return R.err("deploymentId不能为空");

        Map<String, Object> deployment = one("SELECT * FROM network_deployment WHERE id=?", deploymentId);
        if (deployment == null) return R.err("deployment不存在");
        String state = text(deployment.get("state"));
        if ("ROLLED_BACK".equals(state) || "FAILED_ROLLED_BACK".equals(state)) {
            return R.ok(Map.of("deploymentId", deploymentId, "state", state, "message", "无需重复回滚"));
        }

        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> forwards = jdbc.queryForList("""
                SELECT * FROM network_deployment_resource
                WHERE deployment_id=? AND resource_type='FORWARD' AND owned=1
                ORDER BY id DESC
                """, deploymentId);
        for (Map<String, Object> r : forwards) {
            Long id = longValue(r.get("resource_id"));
            if (forwardService.getById(id) == null) continue;
            R deleted = forwardService.deleteForward(id);
            if (deleted.getCode() != 0) errors.add("Forward " + id + ": " + deleted.getMsg());
        }

        List<Map<String, Object>> tunnels = jdbc.queryForList("""
                SELECT * FROM network_deployment_resource
                WHERE deployment_id=? AND resource_type='TUNNEL' AND owned=1
                ORDER BY id DESC
                """, deploymentId);
        for (Map<String, Object> r : tunnels) {
            Long id = longValue(r.get("resource_id"));
            if (tunnelService.getById(id) == null) continue;
            R deleted = tunnelService.deleteTunnel(id);
            if (deleted.getCode() != 0) errors.add("Tunnel " + id + ": " + deleted.getMsg());
        }

        String newState = errors.isEmpty() ? "ROLLED_BACK" : "ROLLBACK_FAILED";
        jdbc.update("UPDATE network_deployment SET state=?,error_message=?,updated_time=? WHERE id=?",
                newState, errors.isEmpty() ? null : truncate(String.join("; ", errors), 1000),
                System.currentTimeMillis(), deploymentId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deploymentId", deploymentId);
        out.put("state", newState);
        out.put("errors", errors);
        return errors.isEmpty() ? R.ok(out) : errorWithData("部分资源回滚失败，需要人工检查", out);
    }

    private long createDeployment(Long chainId, String fingerprint, Map<String, Object> plan,
                                  Integer userId, String userName) {
        Map<String, Object> entry = map(plan.get("entry"));
        Map<String, Object> target = map(plan.get("target"));
        long now = System.currentTimeMillis();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO network_deployment(
                      chain_id,fingerprint,target_host,target_port,entry_node_id,entry_port,state,plan_json,
                      created_by_user_id,created_by_user_name,created_time,updated_time
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, chainId);
            ps.setString(2, fingerprint);
            ps.setString(3, target == null ? null : text(target.get("host")));
            if (target == null || nullableInt(target.get("port")) == null) ps.setNull(4, java.sql.Types.INTEGER);
            else ps.setInt(4, intValue(target.get("port"), 0));
            if (entry == null || nullableLong(entry.get("nodeId")) == null) ps.setNull(5, java.sql.Types.BIGINT);
            else ps.setLong(5, longValue(entry.get("nodeId")));
            if (entry == null || nullableInt(entry.get("plannedPort")) == null) ps.setNull(6, java.sql.Types.INTEGER);
            else ps.setInt(6, intValue(entry.get("plannedPort"), 0));
            ps.setString(7, "APPLYING");
            ps.setString(8, JSON.toJSONString(plan));
            if (userId == null) ps.setNull(9, java.sql.Types.BIGINT); else ps.setLong(9, userId.longValue());
            ps.setString(10, userName);
            ps.setLong(11, now);
            ps.setLong(12, now);
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("deployment主键获取失败");
        return key.longValue();
    }

    private TunnelDto tunnelDto(Map<String, Object> spec) {
        TunnelDto dto = new TunnelDto();
        dto.setName(text(spec.get("name")));
        dto.setInNodeId(longValue(spec.get("inNodeId")));
        dto.setOutNodeId(longValue(spec.get("outNodeId")));
        dto.setType(intValue(spec.get("type"), 2));
        dto.setFlow(intValue(spec.get("flow"), 2));
        dto.setTrafficRatio(BigDecimal.valueOf(doubleValue(spec.get("trafficRatio"), 1.0d)));
        dto.setProtocol(firstNonBlank(text(spec.get("protocol")), "tls"));
        dto.setTcpListenAddr(firstNonBlank(text(spec.get("tcpListenAddr")), "0.0.0.0"));
        dto.setUdpListenAddr(firstNonBlank(text(spec.get("udpListenAddr")), "0.0.0.0"));
        dto.setInterfaceName(blankToNull(text(spec.get("interfaceName"))));
        return dto;
    }

    private Long resolveTunnelRef(Object ref, Map<Integer, Long> segmentTunnelIds) {
        Long direct = nullableLongQuiet(ref);
        if (direct != null) return direct;
        String text = text(ref);
        if (text.startsWith("$segment.") && text.endsWith(".tunnelId")) {
            String middle = text.substring("$segment.".length(), text.length() - ".tunnelId".length());
            try {
                return segmentTunnelIds.get(Integer.parseInt(middle));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long extractForwardId(Object data, ForwardDto dto, Integer userId) {
        if (data instanceof Forward forward && forward.getId() != null) return forward.getId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id FROM forward
                WHERE tunnel_id=? AND in_port=? AND name=? AND user_id=?
                ORDER BY id DESC LIMIT 1
                """, dto.getTunnelId(), dto.getInPort(), dto.getName(), userId);
        return rows.isEmpty() ? null : longValue(rows.get(0).get("id"));
    }

    private void trackResource(long deploymentId, int segment, String type, long resourceId,
                               boolean owned, String name) {
        jdbc.update("""
                INSERT INTO network_deployment_resource(
                  deployment_id,segment_index,resource_type,resource_id,owned,resource_name,created_time
                ) VALUES(?,?,?,?,?,?,?)
                """, deploymentId, segment, type, resourceId, owned ? 1 : 0,
                blankToNull(shortName(name, 120)), System.currentTimeMillis());
    }

    private List<String> rollbackCreated(List<Long> forwardIds, List<Long> tunnelIds) {
        List<String> errors = new ArrayList<>();
        ListIterator<Long> forwardIt = forwardIds.listIterator(forwardIds.size());
        while (forwardIt.hasPrevious()) {
            Long id = forwardIt.previous();
            try {
                if (forwardService.getById(id) == null) continue;
                R result = forwardService.deleteForward(id);
                if (result.getCode() != 0) errors.add("Forward " + id + ": " + result.getMsg());
            } catch (Exception e) {
                errors.add("Forward " + id + ": " + e.getMessage());
            }
        }

        ListIterator<Long> tunnelIt = tunnelIds.listIterator(tunnelIds.size());
        while (tunnelIt.hasPrevious()) {
            Long id = tunnelIt.previous();
            try {
                if (tunnelService.getById(id) == null) continue;
                R result = tunnelService.deleteTunnel(id);
                if (result.getCode() != 0) errors.add("Tunnel " + id + ": " + result.getMsg());
            } catch (Exception e) {
                errors.add("Tunnel " + id + ": " + e.getMessage());
            }
        }
        return errors;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
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

    private static R errorWithData(String msg, Object data) {
        R r = R.err(msg);
        r.setData(data);
        return r;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Long nullableLong(Object value) {
        if (value == null || text(value).isBlank()) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(text(value));
    }

    private static Long nullableLongQuiet(Object value) {
        try {
            return nullableLong(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long longValue(Object value) {
        Long n = nullableLong(value);
        return n == null ? 0L : n;
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

    private static String shortName(String value, int max) {
        String s = value == null ? "" : value.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static class ApplyException extends RuntimeException {
        ApplyException(String message) {
            super(message);
        }
    }
}
