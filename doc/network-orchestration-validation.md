# Network orchestration validation

This branch must remain a Draft PR until the following checks pass:

1. `Backend PR Check / Maven package (Java 21)` succeeds.
2. `/api/v1/network/chain/dry-run` returns a plan with `mutatesDatabase=false` and `mutatesAgentRuntime=false`.
3. A 3-hop preview resolves `7CM -> relay -> egress` without adjacent duplicate nodes.
4. Existing matching type-2 tunnels are emitted as `REUSE_TUNNEL`; missing pairs are emitted as `CREATE_TUNNEL`.
5. Forward actions are ordered downstream-to-upstream and intermediate `remoteAddr` values use `127.0.0.1:<nextInPort>`.
6. No existing `Tunnel`, `Forward`, `Inbound`, user or subscription row changes during dry-run.
7. `main` is not merged until Apply/rollback is separately reviewed.
