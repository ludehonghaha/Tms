# Monitoring / Runtime completion

This branch completes the four monitoring/runtime items that were previously partial:

- Per-user traffic history: minute-level sampling into hourly buckets, 31-day retention, reset-safe flush, indexed queries, admin daily-user page.
- Node status monitoring: restores the status API and exposes Runtime version/protocol state for page refresh recovery.
- Network quality: node-side TCP probes with latency, packet loss and jitter aggregation plus an admin UI.
- Runtime dispatch reliability: WebSocket command ACK was already present; persistence is now atomic, keeps a previous rollback copy, and reports the Runtime version from the single source of truth instead of a stale hard-coded version.

Validation for this branch is defined in `.github/workflows/validate-monitoring.yml` and builds the Java backend, Vite frontend and Go Runtime independently.
