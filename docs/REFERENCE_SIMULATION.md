# Reference simulation for the proof of TD(18) = 10

This is the retained reproduction of the computational part of
[the proof](td18-reduction-count.tex). A complete independent checker run had
already succeeded before this reproduction. The reference emission is complete;
verification of its database on a separate machine will add a new independently
regenerated report. That separate-machine record is not attached yet.

## Machine and software

| Component | Reference configuration |
| --- | --- |
| CPU | Intel Core Ultra 9 185H |
| CPU allocation | 22 logical processors, affinity 0–21, no CPU quota |
| WSL memory | 33,653,166,080 bytes (31.34 GiB); 8 GiB swap |
| GPU | NVIDIA GeForce RTX 4070 Laptop GPU, 8 GiB (8,188 MiB reported) |
| GPU architecture | Compute capability 8.9; native SM 89 and forward PTX |
| Environment | WSL2, Linux 6.18.33.2-microsoft-standard-WSL2, x86-64 |
| JDK | Eclipse Temurin 21.0.4+7; computations run through JShell |
| CUDA toolkit | 12.4.131; GNU g++ 12 host compiler |
| NVIDIA driver | 616.92 as reported by `nvidia-smi` |
| CMake | 4.2.3; Release native build |
| Emitted code revision | `3ee6d121987c734a01872461ae8b0fc92c54fb4a` |
| Source change at launch | [Native architecture compatibility patch](../logs/reference/native-build.patch) |

The native patch permits this CUDA 12.4 / SM 89 build.
The [original settings](../logs/reference/emitter-settings.json) retain every JVM
option and the hashes of the frozen Java classes used by this run.
For current-code runs, `tools/run-local-emitter.sh --build` selects SM 89
explicitly; the CMake default SM 120 target remains available. The maintained
local launchers use the recorded memory and queue limits with all allocated
CPU cores; see [OPERATIONS.md](OPERATIONS.md). New runs have their own revision,
settings and logs and do not replace this historical execution record.
The current emitter does not read the historical `td18.workers` or
`td18.certificateLookupPipelines` properties, so the maintained local emitter
launcher omits them. Its generation and lookup concurrency use the active
worker pools; the checker separately supports configured lookup pipelines.

## Execution parameters

| Parameter | Value |
| --- | --- |
| Scope | `--phase=all`, partitions 1–53, endpoint types 1–74 |
| Input | Fresh `certificates/`; no source database, ranges, or resume |
| CPU / bracelet / inverse workers | 22 / 22 / 22 |
| JVM heap | 2 GiB initial, 16 GiB maximum; JShell host maximum 512 MiB |
| Garbage collection | G1; pause target 200 ms; 8 parallel and 2 concurrent GC threads |
| GPU allocation | Device 0; 8 slots × 768 MiB = 6 GiB queue memory |
| GPU dispatch | 32 reconstruction workers; additional queue capacity 16 |
| Sorting budgets | Endpoints 7; after-two 8; after-one 9; final roots 10 |
| GPU search cutoff | None for reconstruction and final roots |
| CPU prefix | 8,192 triples; reconstruction allowance 10 ms |
| State persistence | 8 writers; 4,194,304 aggregate buffered keys; 512 aggregate queued batches |
| Batch sizes | Generated 65,536 keys; scans 4,096; database writes 524,288 entries |
| Certificate persistence | 8 writers; queue 262,144; flush interval 5 seconds |
| Certificate lookup | 32,768 keys per batch; 4 configured lookup pipelines |
| Root prefetch | 32 search workers; 4 preparation workers; 16 prepared and 32 input batches; 262,144 seen keys; 100 ms advisory GPU allowance |
| Persistence safety | WAL disabled; completed boundaries explicitly flushed |
| Progress / CPU / GPU statistics | 10-second intervals |

CPU utilization sampled during generation was 2,158% for the worker JVM,
using approximately 21.6 of the 22 allocated logical processors. GPU utilization
varies by phase because CPU prefixes discharge most final-root obligations.

## Measured times

All dates below are **16 November 2026**, in **Europe/Lisbon**. UTC and Lisbon
local time coincide on this date. Times refer to application events in the logs;
phase boundaries include their associated generation and persistence work.

- Launcher start: **02:57:32** (`2026-11-16T02:57:32.521365+00:00`).
- Full-scope completion message: **07:35:21.408**.
- Final certificate flush completion: **07:35:21.412**.
- Launcher recorded exit 0: **07:35:21** (`2026-11-16T07:35:21.975246+00:00`).
- Total elapsed time: **4 h 37 min 49 s** (16,669.454 seconds).

| Phase | Start | Finish | Elapsed |
| --- | --- | --- | --- |
| Launch and direct-root emission | 02:57:32 | 03:00:39 | 0 h 03 min 07 s |
| Endpoint emission | 03:00:39 | 03:04:58 | 0 h 04 min 19 s |
| Residual endpoint inverse generation | 03:04:58 | 03:04:59 | 0 h 00 min 01 s |
| After-two search and inverse generation | 03:04:59 | 03:05:24 | 0 h 00 min 25 s |
| After-one search and final-root generation | 03:05:24 | 05:45:29 | 2 h 40 min 05 s |
| Final-root search, persistence and closure | 05:45:29 | 07:35:21 | 1 h 49 min 53 s |

Durations are derived from the exact timestamps in
[the emission log](../logs/reference/emitter.log) and
[the original settings](../logs/reference/emitter-settings.json), rounded to
whole seconds for display. They measure this run and are not estimates for
other machines. Build and small-domain preflight time are excluded.

## Completed scope

| Obligation | Emission count |
| --- | ---: |
| Active root partitions | 53 |
| Complete endpoint annotations | 74 |
| Direct root classes after support clearing | 2 |
| Non-elementary endpoint classes generated | 61,798,462 |
| Residual endpoint classes | 19 |
| After-two inverse classes | 25,544 |
| After-one inverse classes | 6,451,327 |
| Final reconstructed root classes | 1,349,910,890 |
| Unresolved final roots | 0 |

All 53 emitted root rows match the source inventory. The endpoint residuals
were propagated through both `(1,2,2)` and `(0,3,2)` inverse branches on both
endpoints. These are emitter results; the independent checker regenerates its
own counts and replays the database at each requested budget.

## Retained artifacts and separate-machine verification

The completed raw [emission log](../logs/reference/emitter.log),
[GC log](../logs/reference/emitter-gc.log),
[original runtime settings](../logs/reference/emitter-settings.json),
[launcher snapshot](../logs/reference/emitter-launch.sh), and
[versioned reference record](../logs/reference/run.json) are committed under
`logs/reference/`. The launcher snapshot preserves the exact local paths and
options used; it is a historical execution record. Ordinary logs, databases,
compiled classes, and work sets remain untracked. Artifacts from other emission
and verification simulations have been removed from this local checkout.

The closed `certificates/` database is the one to transfer for verification.
Build the checker independently on the separate machine, then run the complete
scope with fresh work; see [VALIDATION.md](VALIDATION.md). When that run finishes,
add its complete log and settings to `logs/reference/`, and record its machine,
source revision, start/finish times, elapsed duration, checked counts and exit
status here. Do not mark that verification complete until its full-scope success
message and exit 0 are present.

Logs, settings, hashes and emitter checkpoints are execution provenance. The
checker establishes computational coverage by regenerating every obligation and
replaying the actual certificate database; no sidecar or obstruction marker can
replace that work. [RESEARCH_PLAN.md](RESEARCH_PLAN.md) remains the full evidence
checklist for the proof.
