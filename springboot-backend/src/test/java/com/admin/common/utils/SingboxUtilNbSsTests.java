package com.admin.common.utils;

import com.admin.entity.Inbound;
import com.admin.entity.InboundUser;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SingboxUtilNbSsTests {

    @Test
    void nbSsKeepsNatEndpointOutOfServerConfigAndDisablesUdp() {
        Inbound inbound = new Inbound();
        inbound.setId(17L);
        inbound.setNodeId(4L);
        inbound.setTag("in-4-40123");
        inbound.setProtocol("nb_ss");
        inbound.setListenPort(40123);
        JSONObject cfg = new JSONObject();
        cfg.put("publicServer", "198.51.100.20");
        cfg.put("publicPort", 13580);
        cfg.put("internalListenAddress", "0.0.0.0");
        cfg.put("method", "2022-blake3-aes-256-gcm");
        cfg.put("password", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        inbound.setConfigJson(cfg.toJSONString());

        JSONObject nodeConfig = SingboxUtil.buildNodeConfig(Collections.singletonList(inbound),
                Collections.emptyMap(), Collections.emptyMap());
        JSONArray inbounds = nodeConfig.getJSONArray("inbounds");
        assertEquals(1, inbounds.size());
        JSONObject generated = inbounds.getJSONObject(0);
        assertEquals("shadowsocks", generated.getString("type"));
        assertEquals("0.0.0.0", generated.getString("listen"));
        assertEquals(40123, generated.getInteger("listen_port"));
        assertEquals("tcp", generated.getString("network"));
        assertFalse(generated.containsKey("publicServer"));
        assertFalse(generated.containsKey("publicPort"));

        String uri = SingboxUtil.buildShadowsocksLink(cfg.getString("publicServer"), cfg.getInteger("publicPort"),
                cfg.getString("method"), cfg.getString("password"), "NoBrand NAT");
        assertTrue(uri.startsWith("ss://"));
        assertTrue(uri.contains("@198.51.100.20:13580#"));
        assertFalse(uri.contains("dialer-proxy"));
        assertFalse(uri.contains("plugin="));

        String yaml = SingboxUtil.buildMihomoShadowsocksProxy("NoBrand NAT", cfg.getString("publicServer"),
                cfg.getInteger("publicPort"), cfg.getString("method"), cfg.getString("password"));
        assertTrue(yaml.contains("type: ss"));
        assertTrue(yaml.contains("server: \"198.51.100.20\""));
        assertTrue(yaml.contains("port: 13580"));
        assertTrue(yaml.contains("udp: false"));
        assertFalse(yaml.contains("dialer-proxy"));
    }

    @Test
    void regularShadowsocksBehaviorRemainsLoopback() {
        Inbound inbound = new Inbound();
        inbound.setTag("in-1-40000");
        inbound.setProtocol("shadowsocks");
        inbound.setListenPort(40000);
        inbound.setConfigJson(JSON.toJSONString(new JSONObject() {{
            put("method", "2022-blake3-aes-256-gcm");
            put("password", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        }}));

        InboundUser user = new InboundUser();
        user.setUserId(7L);
        user.setPassword("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        JSONObject nodeConfig = SingboxUtil.buildNodeConfig(Collections.singletonList(inbound),
                Collections.singletonMap(inbound.getId(), Collections.singletonList(user)), Collections.emptyMap());
        JSONObject generated = nodeConfig.getJSONArray("inbounds").getJSONObject(0);
        assertEquals(SingboxUtil.ssUserLoopback(7L), generated.getString("listen"));
        assertFalse(generated.containsKey("network"));
    }

    @Test
    void nbSsSshRetainsDedicatedLoopbackAndMultiplex() {
        Inbound inbound = new Inbound();
        inbound.setId(18L);
        inbound.setTag("in-4-40000");
        inbound.setProtocol("nb_ss_ssh");
        inbound.setListenPort(40000);
        inbound.setConfigJson(JSON.toJSONString(new JSONObject() {{
            put("method", "2022-blake3-aes-256-gcm");
        }}));
        InboundUser user = new InboundUser();
        user.setUserId(9L);
        user.setPassword("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

        JSONObject nodeConfig = SingboxUtil.buildNodeConfig(Collections.singletonList(inbound),
                Collections.singletonMap(inbound.getId(), Collections.singletonList(user)), Collections.emptyMap());
        JSONObject generated = nodeConfig.getJSONArray("inbounds").getJSONObject(0);
        assertEquals(SingboxUtil.ssUserLoopback(9L), generated.getString("listen"));
        assertEquals(40000, generated.getInteger("listen_port"));
        assertTrue(generated.getJSONObject("multiplex").getBooleanValue("enabled"));
        assertFalse(generated.containsKey("network"));
    }
}
