# Retained reference simulation evidence

The local full emission and the separate-machine full-scope verification are
complete with exit 0. Verification finished on 16 November 2026 at 10:11:21
Lisbon time after 1 h 45 min 43 s, with all 1,349,910,890 reconstructed root
classes checked. See
[the reference record](../../docs/REFERENCE_SIMULATION.md) for its hardware,
configuration, exact timestamps, durations, counts, and verification status.

- `emitter.log`: complete original application log, including all inventory rows and final coverage.
- `emitter-gc.log`: original JVM garbage-collection log.
- `emitter-settings.json`: original runtime settings, revision, frozen class hashes, and exit status.
- `emitter-launch.sh`: exact historical local launcher; paths refer to the emitting machine.
- `native-build.patch`: source patch applied relative to the recorded revision at launch.
- `run.json`: versioned reference summary, measured phase timings, hardware, and native binary hash.
- `checker.log`: complete independent regeneration and certificate-replay log, including the full-scope success message.
- `checker-settings.txt`: verification machine, source revision, JVM options, exact timestamps, integer elapsed seconds and exit status; the final status is `complete`.
- `checker-resources.log`: process CPU/RSS, system memory/swap and memory-pressure samples taken every 30 seconds.
- `checker-gc.log`: verification worker JVM garbage-collection log.
- `checker-launch.sh`: historical Bash/JShell verification launcher, including its automatic documentation append; paths refer to the verifying machine.

Tests were skipped for this verification at the operator's request; no build
logs are retained. The certificate database and generated work sets remain
outside Git. These logs and hashes document execution provenance and are not
required proof inputs for the independent checker.
