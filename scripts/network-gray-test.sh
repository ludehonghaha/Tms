#!/usr/bin/env bash
set -euo pipefail

# Safe-by-default TMS Network Orchestration gray test helper.
#
# Required:
#   TMS_URL=https://panel.example.com
#   TMS_TOKEN=<admin JWT, raw token used by TMS Authorization header>
#   CHAIN_ID=1
#   TARGET_HOST=127.0.0.1
#   TARGET_PORT=8388
#
# Optional:
#   ENTRY_PORT=13511
#   DO_APPLY=0              # 1 = call guarded Apply after successful preflight
#   AUTO_ROLLBACK=0         # 1 = immediately rollback a successful gray Apply
#   UDP_PORTS_CONFIRMED=0   # MUST be 1 before Apply: manually confirm planned ports are UDP-free
#
# Server-side Apply still requires TMS_NETWORK_APPLY_ENABLED=true.
#
# IMPORTANT: current Agent preflight uses TcpPing, so PORT_FREE detects TCP
# listeners only. Before DO_APPLY=1, inspect the planned segment ports from the
# dry-run and confirm on every involved gray host that the same ports are also
# absent from `ss -lunp`. This helper deliberately blocks Apply until the
# operator acknowledges that check with UDP_PORTS_CONFIRMED=1.

: "${TMS_URL:?TMS_URL is required}"
: "${TMS_TOKEN:?TMS_TOKEN is required}"
: "${CHAIN_ID:?CHAIN_ID is required}"
: "${TARGET_HOST:?TARGET_HOST is required}"
: "${TARGET_PORT:?TARGET_PORT is required}"

DO_APPLY="${DO_APPLY:-0}"
AUTO_ROLLBACK="${AUTO_ROLLBACK:-0}"
UDP_PORTS_CONFIRMED="${UDP_PORTS_CONFIRMED:-0}"
ENTRY_PORT="${ENTRY_PORT:-}"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

TMS_URL="${TMS_URL%/}"

post() {
  local path="$1"
  local body="$2"
  curl -fsS \
    -H 'Content-Type: application/json' \
    -H "Authorization: ${TMS_TOKEN}" \
    -d "$body" \
    "${TMS_URL}${path}"
}

body=$(jq -nc \
  --argjson chainId "$CHAIN_ID" \
  --arg targetHost "$TARGET_HOST" \
  --argjson targetPort "$TARGET_PORT" \
  --arg entryPort "$ENTRY_PORT" '
  {
    chainId: $chainId,
    targetHost: $targetHost,
    targetPort: $targetPort,
    strictHealth: true,
    checkEstimatedOutPorts: true,
    requireTargetReachable: true
  }
  + (if $entryPort == "" then {} else {entryPort: ($entryPort|tonumber)} end)
')

echo "===== 1. DRY RUN (read-only) ====="
dry=$(post '/api/v1/network/chain/dry-run' "$body")
echo "$dry" | jq .

if [[ "$(echo "$dry" | jq -r '.code')" != "0" ]]; then
  echo "dry-run failed; stop" >&2
  exit 10
fi

mut_db=$(echo "$dry" | jq -r '.data.safety.mutatesDatabase')
mut_agent=$(echo "$dry" | jq -r '.data.safety.mutatesAgentRuntime')
if [[ "$mut_db" != "false" || "$mut_agent" != "false" ]]; then
  echo "safety invariant failed: dry-run claims mutation" >&2
  exit 11
fi

fingerprint=$(echo "$dry" | jq -r '.data.fingerprint // empty')
if [[ -z "$fingerprint" ]]; then
  echo "missing fingerprint" >&2
  exit 12
fi

echo
echo "===== PLANNED PORTS ====="
echo "$dry" | jq -r '
  .data.segments[]? |
  "segment=\(.index) fromNode=\(.fromNodeId) plannedInPort=\(.plannedInPort) toNode=\(.toNodeId) estimatedOutPort=\(.estimatedOutPort // "-")"
'
echo "Before Apply, confirm every listed planned port is absent from TCP AND UDP listeners on its host."

echo
echo "===== 2. PREFLIGHT (read-only Agent TcpPing) ====="
pre=$(post '/api/v1/network/chain/preflight' "$body")
echo "$pre" | jq .

pre_fp=$(echo "$pre" | jq -r '.data.fingerprint // empty')
ready=$(echo "$pre" | jq -r '.data.readyForApply // false')
if [[ "$pre_fp" != "$fingerprint" ]]; then
  echo "fingerprint changed between dry-run and preflight; rerun" >&2
  exit 20
fi
if [[ "$ready" != "true" ]]; then
  echo "preflight not ready; stop before Apply" >&2
  exit 21
fi

if [[ "$DO_APPLY" != "1" ]]; then
  echo
  echo "PASS: dry-run + preflight only. No Apply was attempted."
  echo "fingerprint=${fingerprint}"
  exit 0
fi

if [[ "$UDP_PORTS_CONFIRMED" != "1" ]]; then
  echo >&2
  echo "Apply blocked: current Agent preflight checks TCP listeners only." >&2
  echo "Run 'ss -lntup' (or at minimum 'ss -lunp') on every gray host for the planned ports," >&2
  echo "then rerun with UDP_PORTS_CONFIRMED=1 only after confirming they are free." >&2
  exit 22
fi

echo
echo "===== 3. APPLY (explicitly requested) ====="
apply_body=$(echo "$body" | jq --arg fp "$fingerprint" '. + {confirm:"APPLY", fingerprint:$fp}')
apply=$(post '/api/v1/network/chain/apply' "$apply_body")
echo "$apply" | jq .

if [[ "$(echo "$apply" | jq -r '.code')" != "0" ]]; then
  echo "Apply failed. Inspect deployment state; server-side rollback may already have run." >&2
  exit 30
fi

deployment_id=$(echo "$apply" | jq -r '.data.deploymentId // empty')
if [[ -z "$deployment_id" ]]; then
  echo "Apply succeeded without deploymentId; stop for manual inspection" >&2
  exit 31
fi

echo "deploymentId=${deployment_id}"

if [[ "$AUTO_ROLLBACK" != "1" ]]; then
  echo "Gray deployment remains ACTIVE. Set AUTO_ROLLBACK=1 only when immediate rollback is intended."
  exit 0
fi

echo
echo "===== 4. ROLLBACK (explicitly requested) ====="
rollback_body=$(jq -nc --argjson id "$deployment_id" '{confirm:"ROLLBACK", deploymentId:$id}')
rollback=$(post '/api/v1/network/deployment/rollback' "$rollback_body")
echo "$rollback" | jq .

if [[ "$(echo "$rollback" | jq -r '.code')" != "0" ]]; then
  echo "Rollback reported an error; manual inspection required" >&2
  exit 40
fi

echo "PASS: gray Apply + rollback completed."
