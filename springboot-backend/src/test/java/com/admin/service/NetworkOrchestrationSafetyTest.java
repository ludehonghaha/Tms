package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NetworkOrchestrationSafetyTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void dryRunIsReadOnlyAndBuildsExecutableTwoHopPlan() {
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

        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("FROM network_chain WHERE id=?")) return List.of(chain);
                if (sql.contains("FROM network_chain_hop")) return List.of(hop1, hop2);
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                throw new AssertionError("dry-run attempted JDBC mutation: " + sql);
            }

            @Override
            public void execute(String sql) {
                throw new AssertionError("dry-run attempted JDBC execute: " + sql);
            }
        };

        NodeService nodeService = mock(NodeService.class);
        NetworkPlanCompiler compiler = new NetworkPlanCompiler();
        ReflectionTestUtils.setField(compiler, "jdbc", jdbc);
        ReflectionTestUtils.setField(compiler, "nodeService", nodeService);

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
    void applyCreatesForwardsDownstreamToUpstreamAndTracksActiveDeployment() {
        installAdminRequestContext();
        ApplyJdbcStub jdbc = new ApplyJdbcStub();
        NetworkPlanCompiler compiler = mock(NetworkPlanCompiler.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ForwardService forwardService = mock(ForwardService.class);
        NetworkPlanApplyService service = applyService(jdbc, compiler, tunnelService, forwardService);

        Map<String, Object> plan = threeHopPlan();
        when(compiler.dryRun(eq(1L), anyMap())).thenReturn(R.ok(plan));
        when(tunnelService.createTunnel(any())).thenReturn(R.ok("created"));
        when(forwardService.createForwardForUser(any(), eq(1), eq("admin"))).thenAnswer(invocation -> {
            ForwardDto dto = invocation.getArgument(0);
            Forward forward = new Forward();
            forward.setId(dto.getName().endsWith("seg2") ? 202L : 201L);
            return R.ok(forward);
        });

        R result = service.apply(applyRequest("fp-gray"));

        assertEquals(0, result.getCode(), result.getMsg());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("ACTIVE", data.get("state"));
        assertEquals(99L, ((Number) data.get("deploymentId")).longValue());
        assertEquals("ACTIVE", jdbc.lastDeploymentState);

        ArgumentCaptor<ForwardDto> captor = ArgumentCaptor.forClass(ForwardDto.class);
        verify(forwardService, times(2)).createForwardForUser(captor.capture(), eq(1), eq("admin"));
        List<ForwardDto> forwards = captor.getAllValues();
        assertEquals("gray-forward-seg2", forwards.get(0).getName());
        assertEquals(102, forwards.get(0).getTunnelId());
        assertEquals("example.com:443", forwards.get(0).getRemoteAddr());
        assertEquals(14001, forwards.get(0).getInPort());
        assertEquals("gray-forward-seg1", forwards.get(1).getName());
        assertEquals(101, forwards.get(1).getTunnelId());
        assertEquals("127.0.0.1:14001", forwards.get(1).getRemoteAddr());
        assertEquals(13001, forwards.get(1).getInPort());
    }

    @Test
    void midApplyFailureRollsBackCreatedForwardThenTunnelsInReverseOrder() {
        installAdminRequestContext();
        ApplyJdbcStub jdbc = new ApplyJdbcStub();
        NetworkPlanCompiler compiler = mock(NetworkPlanCompiler.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ForwardService forwardService = mock(ForwardService.class);
        NetworkPlanApplyService service = applyService(jdbc, compiler, tunnelService, forwardService);

        Map<String, Object> plan = threeHopPlan();
        when(compiler.dryRun(eq(1L), anyMap())).thenReturn(R.ok(plan));
        when(tunnelService.createTunnel(any())).thenReturn(R.ok("created"));
        when(forwardService.createForwardForUser(any(), eq(1), eq("admin")))
                .thenAnswer(invocation -> {
                    ForwardDto dto = invocation.getArgument(0);
                    if (dto.getName().endsWith("seg1")) return R.err("synthetic upstream failure");
                    Forward forward = new Forward();
                    forward.setId(202L);
                    return R.ok(forward);
                });

        Forward createdForward = new Forward();
        createdForward.setId(202L);
        when(forwardService.getById(202L)).thenReturn(createdForward);
        when(forwardService.deleteForward(202L)).thenReturn(R.ok("deleted"));
        Tunnel t101 = new Tunnel(); t101.setId(101L);
        Tunnel t102 = new Tunnel(); t102.setId(102L);
        when(tunnelService.getById(101L)).thenReturn(t101);
        when(tunnelService.getById(102L)).thenReturn(t102);
        when(tunnelService.deleteTunnel(anyLong())).thenReturn(R.ok("deleted"));

        R result = service.apply(applyRequest("fp-gray"));

        assertNotEquals(0, result.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("FAILED_ROLLED_BACK", data.get("state"));
        assertEquals("FAILED_ROLLED_BACK", jdbc.lastDeploymentState);
        verify(forwardService).deleteForward(202L);
        ArgumentCaptor<Long> tunnelDelete = ArgumentCaptor.forClass(Long.class);
        verify(tunnelService, times(2)).deleteTunnel(tunnelDelete.capture());
        assertEquals(List.of(102L, 101L), tunnelDelete.getAllValues());
    }

    @Test
    void rollbackQueriesAndDeletesOnlyOwnedResources() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("FROM network_deployment WHERE id=?")) {
                    return List.of(Map.of("id", 10L, "state", "ACTIVE"));
                }
                if (sql.contains("resource_type='FORWARD'")) {
                    assertTrue(sql.contains("owned=1"), "rollback forward query must filter owned=1");
                    return List.of(Map.of("resource_id", 20L));
                }
                if (sql.contains("resource_type='TUNNEL'")) {
                    assertTrue(sql.contains("owned=1"), "rollback tunnel query must filter owned=1");
                    return List.of();
                }
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                return 1;
            }
        };

        TunnelService tunnelService = mock(TunnelService.class);
        ForwardService forwardService = mock(ForwardService.class);

        NetworkPlanApplyService service = new NetworkPlanApplyService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);

        Forward forward = new Forward();
        forward.setId(20L);
        when(forwardService.getById(20L)).thenReturn(forward);
        when(forwardService.deleteForward(20L)).thenReturn(R.ok("deleted"));

        R result = service.rollback(Map.of("confirm", "ROLLBACK", "deploymentId", 10L));

        assertEquals(0, result.getCode(), result.getMsg());
        verify(forwardService).deleteForward(20L);
        verify(tunnelService, never()).deleteTunnel(anyLong());
    }

    private static NetworkPlanApplyService applyService(JdbcTemplate jdbc,
                                                        NetworkPlanCompiler compiler,
                                                        TunnelService tunnelService,
                                                        ForwardService forwardService) {
        NetworkPlanApplyService service = new NetworkPlanApplyService();
        ReflectionTestUtils.setField(service, "applyEnabled", true);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "compiler", compiler);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);
        return service;
    }

    private static Map<String, Object> applyRequest(String fingerprint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("confirm", "APPLY");
        body.put("chainId", 1L);
        body.put("fingerprint", fingerprint);
        body.put("targetHost", "example.com");
        body.put("targetPort", 443);
        body.put("entryPort", 13001);
        return body;
    }

    private static Map<String, Object> threeHopPlan() {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of(
                "phase", "TUNNEL", "operation", "CREATE_TUNNEL", "segment", 1,
                "spec", tunnelSpec("gray-tunnel-seg1", 1L, 2L)
        ));
        actions.add(Map.of(
                "phase", "TUNNEL", "operation", "CREATE_TUNNEL", "segment", 2,
                "spec", tunnelSpec("gray-tunnel-seg2", 2L, 3L)
        ));
        actions.add(Map.of(
                "phase", "FORWARD", "operation", "CREATE_FORWARD", "segment", 2,
                "spec", forwardSpec("gray-forward-seg2", "$segment.2.tunnelId", "example.com:443", 14001)
        ));
        actions.add(Map.of(
                "phase", "FORWARD", "operation", "CREATE_FORWARD", "segment", 1,
                "spec", forwardSpec("gray-forward-seg1", "$segment.1.tunnelId", "127.0.0.1:14001", 13001)
        ));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("executable", true);
        plan.put("fingerprint", "fp-gray");
        plan.put("entry", Map.of("nodeId", 1L, "plannedPort", 13001));
        plan.put("target", Map.of("host", "example.com", "port", 443));
        plan.put("actions", actions);
        return plan;
    }

    private static Map<String, Object> tunnelSpec(String name, Long inNode, Long outNode) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name);
        spec.put("inNodeId", inNode);
        spec.put("outNodeId", outNode);
        spec.put("type", 2);
        spec.put("flow", 2);
        spec.put("trafficRatio", 1.0);
        spec.put("protocol", "tls");
        spec.put("tcpListenAddr", "0.0.0.0");
        spec.put("udpListenAddr", "0.0.0.0");
        return spec;
    }

    private static Map<String, Object> forwardSpec(String name, String tunnelRef,
                                                   String remoteAddr, int inPort) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name);
        spec.put("tunnelRef", tunnelRef);
        spec.put("remoteAddr", remoteAddr);
        spec.put("inPort", inPort);
        spec.put("strategy", "fifo");
        return spec;
    }

    private static void installAdminRequestContext() {
        String payload = "{\"sub\":\"1\",\"name\":\"admin\",\"role_id\":0}";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "e30." + encoded + ".test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
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

    private static class ApplyJdbcStub extends JdbcTemplate {
        private String lastDeploymentState;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("fingerprint=? AND state='ACTIVE'")) return List.of();
            if (sql.contains("SELECT id,name FROM tunnel WHERE name=?")) {
                String name = String.valueOf(args[0]);
                return List.of(Map.of("id", name.endsWith("seg2") ? 102L : 101L, "name", name));
            }
            return List.of();
        }

        @Override
        public int update(PreparedStatementCreator psc, KeyHolder generatedKeyHolder) {
            generatedKeyHolder.getKeyList().add(Map.of("GENERATED_KEY", 99L));
            return 1;
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("UPDATE network_deployment SET state=")) {
                lastDeploymentState = String.valueOf(args[0]);
            }
            return 1;
        }
    }
}
