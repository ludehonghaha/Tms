# Validation checklist

- [ ] Java backend packages successfully with JDK 21.
- [ ] Vite frontend builds successfully with Node 20.
- [ ] Go Runtime builds successfully with Go 1.21.
- [ ] `/api/v1/node/check-status` returns persisted node status/version.
- [ ] `/api/v1/node/quality` returns latency, packet loss and jitter.
- [ ] `/api/v1/traffic/users` returns daily per-user usage for 7/14/31 days.
- [ ] Manual user flow reset flushes the current sampling delta first.
- [ ] Runtime reports `version.go` version and writes `gost.json` atomically with `gost.json.bak` rollback copy.
