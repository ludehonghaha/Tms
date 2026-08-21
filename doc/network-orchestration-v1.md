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

## Safety boundary

v1 deliberately does **not** rewrite existing `Tunnel`, `Forward`, `Inbound`, user or subscription rows. Existing SS/Mieru/SSH/Reality/subscription behavior is unchanged.

The next phase will compile a network chain into the existing TMS Tunnel/Forward runtime behind `dry-run -> apply`, then add HA/failover only after the plan is verified.

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

## Rollback

The code is isolated to new files. Reverting the feature branch removes the feature without touching existing runtime models. Optional DB cleanup:

```sql
DROP TABLE IF EXISTS network_chain_hop;
DROP TABLE IF EXISTS network_chain;
DROP TABLE IF EXISTS node_group_member;
DROP TABLE IF EXISTS health_probe_sample;
DROP TABLE IF EXISTS health_probe;
DROP TABLE IF EXISTS node_group;
```
