# AGENTS.md

- Before handing off a phase or feature, run the full verification gate: `./scripts/gate.sh`. It wraps `./gradlew gate` (the same task CI runs) with a hang watchdog — this machine has wedged on spinning test JVMs before; the script header explains the knobs and lock-file recovery.
- Review-time judgement rules live in `CODING_STANDARDS.md` (audit honesty, credential ownership, KMP-safe commonMain, test loop teardown). Check diffs against them when reviewing.
- Claims still pending verification on real hardware are tracked in `docs/real-node-verification.md` — consult it before declaring SSH/terminal/update-scan work done, and update it when hardware results come in.
