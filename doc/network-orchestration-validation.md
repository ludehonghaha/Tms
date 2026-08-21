# Network orchestration validation

Current branch: `codex/feature-network-orchestration`

## Automated validation

- `Backend PR Check / Maven test + package (Java 21)`: **PASS**
- Safety test command: `mvn -B -Dtest=NetworkOrchestrationSafetyTest test`
- Package command: `mvn -B -DskipTests package`
- The safety suite verifies:
  1. dry-run is JDBC read-only and produces an executable plan;
  2. Apply is rejected while the feature flag is off;
  3. a stale fingerprint is rejected before JDBC/runtime mutation;
  4. rollback queries only `owned=1` resources and preserves reused tunnels.
- The check runs on this feature branch and pull requests that touch the backend.

## Runtime preflight gate

Before Apply, call `/api/v1/network/chain/preflight` with the same body used for dry-run.

Preflight is read-only and uses the existing Agent `TcpPing` command to:

- verify each planned local TCP port does not currently have a listener;
- optionally check each estimated downstream out-port candidate;
- verify the final selected egress Agent can reach `targetHost:targetPort`.

The response must contain `readyForApply=true`. A failed Agent command is treated as a failed check, not as a free port.

Note: the current preflight detects active TCP listeners, not a kernel-level bind reservation. Apply still performs the authoritative runtime bind and its normal rollback path remains mandatory.

## Required gray-test checklist

This branch must remain a Draft PR until the following runtime checks pass:

1. `/api/v1/network/chain/dry-run` returns a plan with `mutatesDatabase=false` and `mutatesAgentRuntime=false`.
2. A 3-hop preview resolves `7CM -> relay -> egress` without adjacent duplicate nodes.
3. Existing matching type-2 tunnels are emitted as `REUSE_TUNNEL`; missing pairs are emitted as `CREATE_TUNNEL`.
4. Forward actions are ordered downstream-to-upstream and intermediate `remoteAddr` values use `127.0.0.1:<nextInPort>`.
5. No existing `Tunnel`, `Forward`, `Inbound`, user or subscription row changes during dry-run.
6. `/api/v1/network/chain/preflight` returns `readyForApply=true` on the selected non-production chain.
7. Apply remains rejected while `TMS_NETWORK_APPLY_ENABLED` is unset/false.
8. With Apply enabled in an isolated gray environment, an exact fresh fingerprint + `confirm=APPLY` creates a tracked deployment.
9. `confirm=ROLLBACK` removes only `owned=1` deployment resources and preserves reused tunnels.
10. A forced mid-Apply failure ends in `FAILED_ROLLED_BACK` or explicitly surfaces `ROLLBACK_FAILED`.
11. `main` is not merged until Apply/rollback is proven on real non-production nodes.
