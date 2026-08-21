# Network orchestration validation

Current branch: `codex/feature-network-orchestration`

## Automated validation

- `Backend PR Check / Maven package (Java 21)`: **PASS**
- Command: `mvn -B -DskipTests package`
- The check runs on this feature branch and pull requests that touch the backend.

## Required gray-test checklist

This branch must remain a Draft PR until the following runtime checks pass:

1. `/api/v1/network/chain/dry-run` returns a plan with `mutatesDatabase=false` and `mutatesAgentRuntime=false`.
2. A 3-hop preview resolves `7CM -> relay -> egress` without adjacent duplicate nodes.
3. Existing matching type-2 tunnels are emitted as `REUSE_TUNNEL`; missing pairs are emitted as `CREATE_TUNNEL`.
4. Forward actions are ordered downstream-to-upstream and intermediate `remoteAddr` values use `127.0.0.1:<nextInPort>`.
5. No existing `Tunnel`, `Forward`, `Inbound`, user or subscription row changes during dry-run.
6. Apply remains rejected while `TMS_NETWORK_APPLY_ENABLED` is unset/false.
7. With Apply enabled in an isolated gray environment, an exact fresh fingerprint + `confirm=APPLY` creates a tracked deployment.
8. `confirm=ROLLBACK` removes only `owned=1` deployment resources and preserves reused tunnels.
9. A forced mid-Apply failure ends in `FAILED_ROLLED_BACK` or explicitly surfaces `ROLLBACK_FAILED`.
10. `main` is not merged until Apply/rollback is proven on real non-production nodes.
