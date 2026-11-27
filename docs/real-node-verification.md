# Real-node verification checklist

Claims from roadmap v5 (`6785f77..7d3d346`) that rest on code reading and hermetic tests only — no physical homelab or Android device was reachable while they were built. Items stay open until confirmed on hardware; strike one (with the date and the node/OS) when verified.

## Update scans (Phase 3B)

- [ ] apt / dnf / checkupdates / apk probes and parsers against real distros (dnf exit-100 semantics, apk output shape)
- [ ] Privileged OS apply on a real apt node: `DPkg::Lock::Timeout=600` and `--force-confdef --force-confold` behave as intended, and it never reboots
- [ ] buildx index-digest comparison: PINNED / LOCAL / match / mismatch verdicts on the classic store
- [ ] `manifest inspect -v` fallback interpretation (config-digest vs remote digests) — the reading was coherent but unconfirmed

## SSH transport (Phases 1A/1B/2)

- [ ] Host key: TOFU accepted and persisted on first connect; a changed key rejected before any auth data is sent
- [ ] Privileged sudo flows on a real node (`sudo -S -p ''`, prompt suppression, 10-minute probe cache)
- [ ] Two unreachable nodes must not push a healthy node behind them to OFFLINE (per-server connect mutex, ~16 s worst-case hold)
- [ ] Test SSH with transient credentials writes nothing to the vault

## Files (Phase 6)

- [ ] Live JSch binary transfer: progress ticks, cancel mid-read, `.bak` abort on overwrite
- [ ] FileKit dialogs through ContentResolver (SAF) on device

## Terminal (Phases 1C/7)

- [ ] OSC-1337 cursor enforcement observed in a live session (compiles; unobservable hermetically)
- [ ] Reconnect under real network loss (airplane mode): 3-attempt backoff, banner, recovery
- [ ] Foreground service reports `connectedDevice` in `dumpsys`, and `onTimeout` notifies cleanly

## On-device UX (Phases 5/8)

- [ ] Header collapse: fling feel and spring settle — seen failing on a running build (2026-11-27): the header never expanded again once collapsed, showed clipped content while collapsed, and the hero pills ignored taps when expanded. Fixed and pinned hermetically by `CollapsingDetailHeaderTest` (Robolectric layout, gestures and touch hit-testing); the fix itself still needs a device pass
- [ ] TalkBack across the collapsing header, compact row and pinned pills
- [ ] Tablet re-measure on rotation/resize (840/780 dp breakpoints)
- [ ] Widget destructive tap deep-links into node detail with the action staged; confirming audits the execution, dismissing audits `BLOCKED`

## App-level

- [ ] `adb install -r` over a v1 database: nodes, snippets, tunnels and the audit hash chain intact (schema v2 migration)
- [ ] POST_NOTIFICATIONS runtime prompt on first launch (Android 13+), once per install
- [ ] Notification taps deep-link to the right node and screen

Known feature deferrals (not verification items) live in the v0.0.2 release notes: SOCKS5 dynamic forwarding (JSch exposes no API) and the Podman update scan.
