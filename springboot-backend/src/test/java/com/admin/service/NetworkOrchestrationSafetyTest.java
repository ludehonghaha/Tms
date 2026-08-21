package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NetworkOrchestrationSafetyTest {

    @Test
    void dryRunIsReadOnlyAndBuildsExecutableTwoHopPlan() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NodeService nodeService = mock(NodeService.class);
        NetworkPlanCompiler compiler = new NetworkPlanCompiler();
        ReflectionTestUtils.setField(compiler, "jdbc", jdbc);
        ReflectionTestUtils.setField(compiler, "nodeService", nodeService);

        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("id", 1L);
        chain.put("name", "gray-a-b");
        chain.put("protocol", "AUTO");
        chain.put("status", 1);
        chain.put("updated_time", 123456L);

        Map<String, Object> hop1 = new LinkedHashMap<>();
        hop1.put("hop_order", 1);
        hop1.put("hop_type", "NODE");
        hop1.put("node_id", 1L);
        hop1.put("transport", "AUTO");
        hop1.put("health_probe_id", null);

        Map<String, Object> hop2 = new LinkedHashMap<>();
        hop2.put("hop_order", 2);
        hop2.put("hop_type", "NODE");
        hop2.put("node_id", 2L);
        hop2.put("transport", "AUTO");
        hop2.put("health_probe_id", null);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM network_chain WHERE id=?")) return List.of(chain);
            if (sql.contains("FROM network_chain_hop")) return List.of(hop1, hop2);
            // no reusable tunnel and no reserved ports in this synthetic gray plan
            return List.of();
        });

        Node a = node(1L, "A", "10.0.0.1", 13000, 13010);
        Node b = node(2L, "B", "10.0.0.2", 14000, 14010);
        when(nodeService.getById(1L)).thenReturn(a);
        when(nodeService.getById(2L)).thenReturn(b);

        R result = compiler.dryRun(1L, Map.of(
                "targetHost", "127.0.0.1",
                "targetPort", 8388,
                "entryPort", 13005,
                "strictHealth", true
        ));

        assertEquals(0, result.getCode(), result.getMsg());
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) result.getData();
        assertEquals(Boolean.TRUE, plan.get("executable"));
        assertNotNull(plan.get("fingerprint"));

        @SuppressWarnings("unchecked")
        Map<String, Object> safety = (Map<String, Object>) plan.get("safety");
        assertEquals(Boolean.FALSE, safety.get("mutatesDatabase"));
        assertEquals(Boolean.FALSE, safety.get("mutatesAgentRuntime"));
        assertEquals(Boolean.FALSE, safety.get("changesExistingTunnelForward"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) plan.get("actions");
        assertEquals("CREATE_TUNNEL", actions.get(0).get("operation"));
        assertEquals("CREATE_FORWARD", actions.get(1).get("operation"));

        // Compiler must only read JDBC state; no INSERT/UPDATE/DELETE is allowed here.
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void applyIsDisabledByDefaultBeforeAnyMutation() {
        NetworkPlanApplyService service = new NetworkPlanApplyService();
        ReflectionTestUtils.setField(service, "applyEnabled", false);

        R result = service.apply(Map.of());

        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("默认关闭"));
    }

    @Test
    void staleFingerprintIsRejectedBeforeJdbcOrRuntimeMutation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NetworkPlanCompiler compiler = mock(NetworkPlanCompiler.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ForwardService forwardService = mock(ForwardService.class);

        NetworkPlanApplyService service = new NetworkPlanApplyService();
        ReflectionTestUtils.setField(service, "applyEnabled", true);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "compiler", compiler);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);

        Map<String, Object> freshPlan = new LinkedHashMap<>();
        freshPlan.put("executable", true);
        freshPlan.put("fingerprint", "fresh-fingerprint");
        when(compiler.dryRun(eq(1L), anyMap())).thenReturn(R.ok(freshPlan));

        R result = service.apply(Map.of(
                "confirm", "APPLY",
                "chainId", 1L,
                "fingerprint", "stale-fingerprint"
        ));

        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("计划已变化"));
        verifyNoInteractions(jdbc, tunnelService, forwardService);
    }

    @Test
    void rollbackQueriesAndDeletesOnlyOwnedResources() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NetworkPlanCompiler compiler = mock(NetworkPlanCompiler.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ForwardService forwardService = mock(ForwardService.class);

        NetworkPlanApplyService service = new NetworkPlanApplyService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "compiler", compiler);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM network_deployment WHERE id=?")) {
                return List.of(Map.of("id", 10L, "state", "ACTIVE"));
            }
            if (sql.contains("resource_type='FORWARD'")) {
                assertTrue(sql.contains("owned=1"), "rollback forward query must filter owned=1");
                return List.of(Map.of("resource_id", 20L));
            }
            if (sql.contains("resource_type='TUNNEL'")) {
                assertTrue(sql.contains("owned=1"), "rollback tunnel query must filter owned=1");
                // Synthetic deployment reused an existing Tunnel, so there is no owned Tunnel row.
                return List.of();
            }
            return List.of();
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        Forward forward = new Forward();
        forward.setId(20L);
        when(forwardService.getById(20L)).thenReturn(forward);
        when(forwardService.deleteForward(20L)).thenReturn(R.ok("deleted"));

        R result = service.rollback(Map.of("confirm", "ROLLBACK", "deploymentId", 10L));

        assertEquals(0, result.getCode(), result.getMsg());
        verify(forwardService).deleteForward(20L);
        verify(tunnelService, never()).deleteTunnel(anyLong());
    }

    private static Node node(Long id, String name, String serverIp, int portStart, int portEnd) {
        Node n = new Node();
        n.setId(id);
        n.setName(name);
        n.setServerIp(serverIp);
        n.setIp(serverIp);
        n.setPortSta(portStart);
        n.setPortEnd(portEnd);
        n.setStatus(1);
        return n;
    }
}
