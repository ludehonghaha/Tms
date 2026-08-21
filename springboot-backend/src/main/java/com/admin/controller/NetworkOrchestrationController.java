package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.NetworkOrchestrationService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/network")
public class NetworkOrchestrationController {

    @Resource
    private NetworkOrchestrationService networkService;

    @LogAnnotation
    @RequireRole
    @PostMapping("/group/list")
    public R listGroups() {
        return networkService.listGroups();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/group/save")
    public R saveGroup(@RequestBody Map<String, Object> body) {
        return networkService.saveGroup(body);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/group/delete")
    public R deleteGroup(@RequestBody Map<String, Object> body) {
        return networkService.deleteGroup(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/group/member/save")
    public R saveGroupMember(@RequestBody Map<String, Object> body) {
        return networkService.saveGroupMember(body);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/group/member/delete")
    public R deleteGroupMember(@RequestBody Map<String, Object> body) {
        return networkService.deleteGroupMember(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/group/select")
    public R selectGroupMember(@RequestBody Map<String, Object> body) {
        return networkService.selectGroupMember(Long.valueOf(body.get("groupId").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/chain/list")
    public R listChains() {
        return networkService.listChains();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/chain/save")
    public R saveChain(@RequestBody Map<String, Object> body) {
        return networkService.saveChain(body);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/chain/delete")
    public R deleteChain(@RequestBody Map<String, Object> body) {
        return networkService.deleteChain(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/probe/list")
    public R listProbes() {
        return networkService.listProbes();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/probe/save")
    public R saveProbe(@RequestBody Map<String, Object> body) {
        return networkService.saveProbe(body);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/probe/delete")
    public R deleteProbe(@RequestBody Map<String, Object> body) {
        return networkService.deleteProbe(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/probe/run")
    public R runProbe(@RequestBody Map<String, Object> body) {
        return networkService.runProbe(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/probe/samples")
    public R probeSamples(@RequestBody Map<String, Object> body) {
        Long probeId = Long.valueOf(body.get("probeId").toString());
        int limit = body.get("limit") == null ? 100 : Integer.parseInt(body.get("limit").toString());
        return networkService.listProbeSamples(probeId, limit);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/topology")
    public R topology() {
        return networkService.topology();
    }
}
