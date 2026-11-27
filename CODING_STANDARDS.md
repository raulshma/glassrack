# Coding standards

Rules enforced at review time. Each is a judgement call no build check can substitute for; the mechanical checks live in the gate (`./gradlew gate`, wrapped locally by `./scripts/gate.sh`, run by CI). When reviewing a diff, check it against every rule here; when implementing, follow the surrounding code and these rules.

## Tests stop the loops they start

A fake or controller that runs a background loop exposes `stop()`, and the test that constructs it calls `stop()` in `finally` before returning. A loop that never suspends — or an assertion that throws before teardown — spins the test JVM instead of failing the test: the suite once hung for 37 minutes on exactly this (idle-prune loop, then again on log-follow). Product-side counterpart: loops poll with `delay()` (cancellable, virtual-time-friendly), never `Thread.sleep`, and disconnect in `finally`.

## Audit entries are honest

Every side-effecting action writes exactly one audit entry with its real outcome (`SUCCESS`/`FAILED`/`TIMEOUT`/`CANCELLED`/`BLOCKED`) and its real duration. Measured, never fabricated; a path that refuses to run records `BLOCKED` or `FAILED`, never silence; `TIMEOUT`/`CANCELLED` are written under `NonCancellable` so cancellation cannot skip them. When reviewing a new action, ask what its entry says when it is cancelled, fails, or is refused — tunnel close shipped with no entry and tunnel open with a fabricated 45 ms `SUCCESS`.

## Probes never persist; Save owns persistence

A `Test` connection is transient: a dedicated never-pooled session, the entered password used in memory only, always disconnected, nothing written. `Save` owns all credential persistence (set/change/clear, SSH key metadata). A new credential flow states which side owns the write and which only reads at use time.

## Secrets reach privileged commands via stdin only

Sudo passwords ride `stdinText` on `sudo -S -p ''` (prompt suppressed, EOF after delivery) — never the command string, argv, or any captured output. A new privileged path ships with a leak-scan test asserting the password appears on exactly the credential-delivery surfaces and nowhere else; `PrivilegedExecutionTest` is the reference shape.

## commonMain stays KMP-safe

No JVM-only APIs in commonMain. `Dispatchers.IO` shipped there once and silently broke the iOS build for two phases; dispatch through the expect/actual seam (`com.raulshma.grack.feature.servers.TransferDispatcher`) instead of JVM-only singletons. The gate's iOS compiles catch violations mechanically now, but reach for the seam first.

## Synthetic fixtures stay marked until a real node confirms them

Parsers and probes written from documentation rather than observed hardware carry that mark (in the fixture/test), and their open items are tracked in `docs/real-node-verification.md`. Confirming on hardware removes the mark — nowhere else.
