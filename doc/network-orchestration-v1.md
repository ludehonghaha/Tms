# TMS Network Orchestration v1

Base branch: `main`
Feature branch: `codex/feature-network-orchestration`

This is an additive, clean-room implementation inspired by ForwardX's resource model. No ForwardX source code is copied.

## Added in v1

- Node groups: `INGRESS`, `EGRESS`, `TRANSIT`, `GENERIC`
- Selection strategies: `PRIORITY`, `ROUND_ROBIN`, `WEIGHTED`, `LOWEST_RTT`
- Group member priority / weight / health-probe binding
- Multi-hop chains with `NODE` and `GROUP` hops
- Agent-executed TCP health probes using the existing TMS `TcpPing` WebSocket command
- Probe history for RTT/loss monitoring
- Periodic health-probe scheduler
- Read-only topology aggregation endpoint
- Read-only Network Chain -> existing `Tunnel` / `Forward` dry-run compiler
- Reuse detection for compatible existing tunnel rows
- Candidate port planning that avoids existing TMS Forward/Inbound reservations
- Reverse dependency plan for multi-hop forwarding
- Stable plan fingerprint
- Guarded Apply endpoint with fingerprint revalidation
- Deployment/resource audit tables
- Reverse-order rollback for resources created by a deployment
- Backend PR compile CI on Java 21

## Safety boundary

The orchestration control plane is additive and automatic failover is still disabled.

`/chain/dry-run` is strictly read-only:

- no Tunnel row is created
- no Forward row is created
- no Agent command is sent
- no current SS/Mieru/SSH/Reality/subscription runtime is changed

`/chain/apply` has multiple independent safety gates:

1. `tms.network.apply-enabled` defaults to `false`.
2. Environment variable `TMS_NETWORK_APPLY_ENABLED=true` is required to enable Apply.
3. The request must include `confirm=APPLY`.
4. The request must include the exact current dry-run `fingerprint`.
5. Dry-run is recomputed immediately before any mutation; a changed fingerprint aborts Apply.
6. Created Tunnel/Forward resource IDs are recorded in deployment audit tables.
7. Failure triggers reverse-order cleanup of resources created by that deployment.
8. Reused existing tunnels are recorded as `owned=0` and are never deleted by deployment rollback.

Rollback requires `confirm=ROLLBACK` and can remain available even when Apply is disabled.

## Why multi-hop is compiled from downstream to upstream

Existing TMS tunnel forwarding already works as:

```text
入口节点
  -> GOST chain
  -> 出口节点 remote service
  -> Forward.remoteAddr
```

Therefore a chain such as:

```text
7CM -> HK -> JP -> target
```

can be stacked as:

```text
1. HK -> JP forward: remoteAddr = target
2. 7CM -> HK forward: remoteAddr = 127.0.0.1:<HK->JP inPort>
```

The compiler emits Forward actions in this reverse order so the downstream listener exists before the upstream segment is created.

## API

All endpoints are admin protected and use POST under `/api/v1/network`:

- `/group/list`
- `/group/save`
- `/group/delete`
- `/group/member/save`
- `/group/member/delete`
- `/group/select`
- `/chain/list`
- `/chain/save`
- `/chain/delete`
- `/chain/dry-run`
- `/chain/apply`
- `/deployment/list`
- `/deployment/rollback`
- `/probe/list`
- `/probe/save`
- `/probe/delete`
- `/probe/run`
- `/probe/samples`
- `/topology`

## Example exit group

```json
{
  "name": "JP-出口",
  "role": "EGRESS",
  "strategy": "LOWEST_RTT",
  "failoverEnabled": 1,
  "status": 1
}
```

## Example chain

```json
{
  "name": "7CM-HK-JP",
  "protocol": "AUTO",
  "failoverEnabled": 1,
  "status": 1,
  "hops": [
    {"hopOrder": 1, "hopType": "NODE", "nodeId": 7, "transport": "AUTO"},
    {"hopOrder": 2, "hopType": "NODE", "nodeId": 12, "transport": "AUTO"},
    {"hopOrder": 3, "hopType": "GROUP", "groupId": 2, "transport": "AUTO"}
  ]
}
```

## Dry-run examples

Topology-only preview:

```json
{
  "chainId": 1,
  "strictHealth": true
}
```

Executable-plan preview:

```json
{
  "chainId": 1,
  "targetHost": "127.0.0.1",
  "targetPort": 8388,
  "entryPort": 13511,
  "strictHealth": true
}
```

The response contains:

```text
resolvedHops  selected NODE/GROUP members and health state
segments      pairwise TMS tunnel plan and candidate ports
actions       CREATE/REUSE_TUNNEL followed by reverse CREATE_FORWARD plan
entry         public entry node/address/planned port
target        final remote target
fingerprint   stable plan identity for Apply validation
warnings      non-fatal planning caveats
```

`estimatedOutPort` is informational only. Existing `ForwardService` owns final out-port allocation. Planned `inPort` is explicitly supplied during Apply because it is the dependency-stable value used by the previous segment.

## Apply example

First generate a fresh executable dry-run and copy its fingerprint. Apply stays disabled until the server is explicitly configured with:

```bash
TMS_NETWORK_APPLY_ENABLED=true
```

Then send the same plan inputs plus confirmation:

```json
{
  "chainId": 1,
  "targetHost": "127.0.0.1",
  "targetPort": 8388,
  "entryPort": 13511,
  "strictHealth": true,
  "fingerprint": "<exact dry-run fingerprint>",
  "confirm": "APPLY"
}
```

The deployment is tracked as `APPLYING -> ACTIVE` on success. If creation fails, resources already created by this deployment are removed in reverse order and state becomes `FAILED_ROLLED_BACK`; incomplete cleanup becomes `ROLLBACK_FAILED` for operator attention.

## Manual rollback example

```json
{
  "deploymentId": 1,
  "confirm": "ROLLBACK"
}
```

Only `owned=1` resources created by this deployment are removed. Reused existing tunnels are preserved.

## Still intentionally not implemented

- automatic failover that rewrites an ACTIVE production route
- automatic failback
- unattended background Apply
- destructive cleanup of legacy Tunnel/Forward data

Those should only be added after a real 3-host gray test proves Apply + rollback.

## Rollback of the feature branch

The implementation is isolated to additive orchestration files. Reverting the feature branch removes the feature without changing existing runtime models. Optional DB cleanup after all deployments are rolled back:

```sql
DROP TABLE IF EXISTS network_deployment_resource;
DROP TABLE IF EXISTS network_deployment;
DROP TABLE IF EXISTS network_chain_hop;
DROP TABLE IF EXISTS network_chain;
DROP TABLE IF EXISTS node_group_member;
DROP TABLE IF EXISTS health_probe_sample;
DROP TABLE IF EXISTS health_probe;
DROP TABLE IF EXISTS node_group;
```
