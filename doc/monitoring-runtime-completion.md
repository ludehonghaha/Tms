# Monitoring / Runtime completion

This branch completes the four monitoring/runtime items that were previously partial:

- Per-user traffic history: minute-level sampling into hourly buckets, 31-day retention, reset-safe flush, indexed queries, admin daily-user page.
- Node status monitoring: restores the status API and exposes Runtime version/protocol state for page refresh recovery.
- Network quality: node-side TCP probes with latency, packet loss and jitter aggregation plus an admin UI.
- Runtime dispatch reliability: WebSocket command ACK was already present; persistence is now atomic, keeps a previous rollback copy, failed commands are not persisted, and the Runtime version comes from the single source of truth instead of a stale hard-coded version.

Before merge, PR validation independently builds the three deliverables with the same major toolchains used by the project:

- Java backend: JDK 21, `mvn -B -DskipTests package`
- Vite frontend: Node 20, `npm install --legacy-peer-deps` then `npm run build`
- Go Runtime: Go 1.23, `go build ./...`
