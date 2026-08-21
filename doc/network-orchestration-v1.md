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
- Stable plan fingerprint for the future Apply phase

## Safety boundary

The orchestration control plane does **not** rewrite existing `Tunnel`, `Forward`, `Inbound`, user or subscription rows.

`/chain/dry-run` is strictly read-only:

- no Tunnel row is created
- no Forward row is created
- no Agent command is sent
- no current SS/Mieru/SSH/Reality/subscription runtime is changed

The next phase will accept a validated dry-run fingerprint, re-check health/ports/chain version, then create the planned Tunnel/Forward resources behind an explicit Apply API. Automatic failover remains disabled until Apply/rollback is proven safe.

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

can be stacked safely as:

```text
1. HK -> JP forward: remoteAddr = target
2. 7CM -> HK forward: remoteAddr = 127.0.0.1:<HK->JP inPort>
```

The dry-run emits Forward actions in this reverse order so the downstream listener exists before the upstream segment is created.

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

Topology-only preview (does not require a target):

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
fingerprint   stable plan identity for future Apply validation
warnings      non-fatal planning caveats
```

`estimatedOutPort` is informational only. Existing `ForwardService` owns final out-port allocation, so Apply must re-check OS/runtime port conflicts. Planned `inPort` is the dependency-stable value used to stack upstream segments.

## Rollback

The implementation is isolated to additive orchestration files plus the orchestration controller endpoint. Reverting the feature branch removes the feature without changing existing runtime models. Optional DB cleanup:

```sql
DROP TABLE IF EXISTS network_chain_hop;
DROP TABLE IF EXISTS network_chain;
DROP TABLE IF EXISTS node_group_member;
DROP TABLE IF EXISTS health_probe_sample;
DROP TABLE IF EXISTS health_probe;
DROP TABLE IF EXISTS node_group;
```
