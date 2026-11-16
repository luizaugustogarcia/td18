# Retained reference simulation evidence

The local full emission is complete with exit 0. See
[the reference record](../../docs/REFERENCE_SIMULATION.md) for its hardware,
configuration, exact timestamps, durations, counts, and verification status.

- `emitter.log`: complete original application log, including all inventory rows and final coverage.
- `emitter-gc.log`: original JVM garbage-collection log.
- `emitter-settings.json`: original runtime settings, revision, frozen class hashes, and exit status.
- `emitter-launch.sh`: exact historical local launcher; paths refer to the emitting machine.
- `native-build.patch`: source patch applied relative to the recorded revision at launch.
- `run.json`: versioned reference summary, measured phase timings, hardware, and native binary hash.

Add finished separate-machine checker logs and settings here after independent
full-scope verification. That verification is not attached yet. The certificate
database and generated work sets remain outside Git; these logs and hashes are
not required proof inputs for the independent checker.
