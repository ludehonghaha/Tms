package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class NetworkOrchestrationChainValidationTest {

    @Test
    void invalidSecondHopRejectsUpdateBeforeAnyDatabaseReadOrWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NodeService nodeService = mock(NodeService.class);

        Node valid = new Node();
        valid.setId(1L);
        valid.setName("valid-node");
        when(nodeService.getById(1L)).thenReturn(valid);
        when(nodeService.getById(999L)).thenReturn(null);

        NetworkOrchestrationService service = new NetworkOrchestrationService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);

        R result = service.saveChain(Map.of(
                "id", 77L,
                "name", "existing-gray-chain",
                "protocol", "AUTO",
                "hops", List.of(
                        Map.of(
                                "hopOrder", 1,
                                "hopType", "NODE",
                                "nodeId", 1L,
                                "transport", "AUTO",
                                "status", 1
                        ),
                        Map.of(
                                "hopOrder", 2,
                                "hopType", "NODE",
                                "nodeId", 999L,
                                "transport", "AUTO",
                                "status", 1
                        )
                )
        ));

        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("节点不存在"));
        verify(nodeService).getById(1L);
        verify(nodeService).getById(999L);
        verifyNoInteractions(jdbc);
    }
}
