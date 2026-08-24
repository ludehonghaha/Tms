# Validation checklist

- [x] Java backend packages successfully with JDK 21.
- [x] Vite frontend builds successfully with Node 20.
- [x] Go Runtime builds successfully with Go 1.23.
- [ ] `/api/v1/node/check-status` verified against a deployed Runtime.
- [ ] `/api/v1/node/quality` verified against a deployed Runtime and returns latency, packet loss and jitter.
- [ ] `/api/v1/traffic/users` verified against production-like traffic for 7/14/31 days.
- [ ] Manual user flow reset verified end-to-end with the current sampling delta flushed first.
- [ ] Runtime atomic persistence and `gost.json.bak` rollback copy verified on a deployed node.

The unchecked items require a running panel/Runtime pair and real traffic; compile-time validation alone cannot prove those integration paths.
