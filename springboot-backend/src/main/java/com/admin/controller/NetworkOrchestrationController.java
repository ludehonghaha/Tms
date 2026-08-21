package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.NetworkOrchestrationService;
import com.admin.service.NetworkPlanApplyService;
import com.admin.service.NetworkPlanCompiler;
import com.admin.service.NetworkPreflightService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/network")
public class NetworkOrchestrationController {

    @Resource
    private NetworkOrchestrationService networkService;

    @Resource
    private NetworkPlanCompiler networkPlanCompiler;

    @Resource
    private NetworkPreflightService networkPreflightService;

    @Resource
    private NetworkPlanApplyService networkPlanApplyService;

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

    /**
     * Compile a chain into a read-only Tunnel/Forward execution plan.
     * This endpoint never changes tunnel/forward rows or Agent runtime state.
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/chain/dry-run")
    public R dryRunChain(@RequestBody Map<String, Object> body) {
        Object chainId = body.get("chainId");
        if (chainId == null) return R.err("chainId不能为空");
        return networkPlanCompiler.dryRun(Long.valueOf(chainId.toString()), body);
    }

    /**
     * Read-only runtime preflight using the existing Agent TcpPing command.
     * It checks planned local TCP ports and final target reachability.
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/chain/preflight")
    public R preflightChain(@RequestBody Map<String, Object> body) {
        Object chainId = body.get("chainId");
        if (chainId == null) return R.err("chainId不能为空");
        return networkPreflightService.preflight(Long.valueOf(chainId.toString()), body);
    }

    /**
     * Apply is disabled by default even when this code is deployed.
     * Requires TMS_NETWORK_APPLY_ENABLED=true, confirm=APPLY and exact fingerprint.
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/chain/apply")
    public R applyChain(@RequestBody Map<String, Object> body) {
        return networkPlanApplyService.apply(body);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/deployment/list")
    public R listDeployments() {
        return networkPlanApplyService.listDeployments();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/deployment/rollback")
    public R rollbackDeployment(@RequestBody Map<String, Object> body) {
        return networkPlanApplyService.rollback(body);
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
