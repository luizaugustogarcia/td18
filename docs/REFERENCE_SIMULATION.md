# Reference simulation for the proof of TD(18) = 10

This is the retained reproduction of the computational part of
[the proof](td18-reduction-count.tex). A complete independent checker run had
already succeeded before this reproduction. Both the reference emission and
independent full-scope verification of its database on a separate machine are
complete with exit 0. Their distinct configurations, counts and execution
records are retained below.

## Emission machine and software

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
For current-code runs, `tools/run-local-emitter.sh --build` defaults to SM 89;
the CMake default SM 120 target and explicit build overrides remain available.
The maintained local launchers use the recorded memory and queue limits with all allocated
CPU cores; see [OPERATIONS.md](OPERATIONS.md). New runs have their own revision,
settings and logs and do not replace this historical execution record.
The current emitter does not read the historical `td18.workers` or
`td18.certificateLookupPipelines` properties, so the maintained local emitter
launcher omits them. Its generation and lookup concurrency use the active
worker pools; the checker separately supports configured lookup pipelines.

## Emission execution parameters

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

## Emission measured times

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

| Obligation | Emission count | Independent verification count |
| --- | ---: | ---: |
| Active root partitions | 53 | 53 |
| Complete endpoint annotations | 74 | 74 |
| Elementary endpoint annotations | 26 | 26 |
| Non-elementary endpoint annotations | 48 | 48 |
| Direct root classes after support clearing | 2 | 2 |
| Non-elementary endpoint classes generated | 61,798,462 | 61,798,462 |
| Residual endpoint classes | 19 | 19 |
| After-two inverse classes | 25,544 | 25,544 |
| After-one inverse classes | 6,451,327 | 6,451,327 |
| Final reconstructed root classes | 1,349,910,890 | 1,349,910,890 |
| Unresolved final roots | 0 | 0 |

All 53 emitted root rows match the source inventory. The endpoint residuals
were propagated through both `(1,2,2)` and `(0,3,2)` inverse branches on both
endpoints. The independent checker regenerated all 53 root rows and compared
their retained unoriented, retained oriented and support-cleared counts with
the source inventory. It regenerated the complete structural endpoint cover;
each of the 48 non-elementary endpoint counts was checked against Burnside.
It replayed the database at budgets 10 for direct roots, 7 for endpoints,
8 and 9 for intermediate inverse states, and 10 for final roots. Every endpoint
miss was propagated through the applicable inverse branches, without assuming
an obstruction list. The final report confirms complete coverage.

## Verification machine and execution parameters

The separate verification ran on the transferred, read-only `certificates/`
database with fresh checker work. The checker has no emitter artifact
dependency and did not use CUDA. Its Java sources at the recorded verification
revision are unchanged from the emitted code revision above.

| Component or parameter | Verification configuration |
| --- | --- |
| CPU | AMD Ryzen 7 8840HS with Radeon 780M Graphics; 8 cores, 16 threads |
| CPU allocation | 16 logical processors; affinity 0–15 |
| WSL memory | 12,540,600,320 bytes (11.68 GiB); 3 GiB swap |
| Storage | NVMe, as reported by the operator |
| Environment | WSL2, Linux 6.18.33.2-microsoft-standard-WSL2, x86-64 |
| JDK | Ubuntu OpenJDK 21.0.13.0.3+1-1-26.04.1; JShell |
| Checker source revision | `5871a309f170d45cbd84d268f41952fb7815c667` |
| Scope | `--phase=all`; all 53 root partitions and 74 endpoint annotations; no ranges or resume |
| Work | `checker/target/reference-verification-20261116T082538Z/obligations/` |
| CPU / bracelet workers | 16 / 16 |
| JVM heap | 512 MiB initial, 6 GiB maximum; JShell host maximum 256 MiB |
| Garbage collection | G1; pause target 200 ms; 8 parallel and 2 concurrent GC threads |
| Certificate lookup | 32,768 keys per batch; 4 pipelines |
| Inverse-state persistence | 4 writers; 1,048,576 aggregate buffered keys; 64 aggregate queued batches |
| Batch sizes | Generated 8,192 keys; scans up to 4,096; persistence buffers up to 262,144 entries per writer |
| Persistence safety | Certificate database read-only; inverse sets use completed external-SST ingestion without WAL writes |
| Monitoring | Checker and GC logs; 10-second phase progress; 30-second process and system resource samples |
| Tests | Skipped at the operator's request; no CPU or CUDA test execution claimed for this verification |

The retained process samples show worker JVM lifetime-average CPU utilization
reaching 1,555% (about 15.6 logical processors). Its sampled peak resident memory was
6,902,688 KiB (6.58 GiB), which includes memory outside the Java heap.
System-wide swap use reached 1,767,948,288 bytes (1.65 GiB); this is not a
process-specific swap measurement. Full samples, including memory pressure,
are retained in [the resource log](../logs/reference/checker-resources.log).

## Verification measured times and result

All dates are **16 November 2026**, in **Europe/Lisbon**, which coincides with
UTC on this date.

- Launcher start: **08:25:38.230656275**.
- Final-root replay completed: **10:11:19.115**.
- The log contains `All regenerated obligations in the complete joint-selection scope are verified.`
- Launcher recorded exit **0** at **10:11:21.857738762**.
- Recorded elapsed time: **1 h 45 min 43 s** (6,343 integer seconds).

| Verification phase | Start | Finish | Elapsed |
| --- | --- | --- | --- |
| Launch and independent root regeneration/replay | 08:25:38 | 08:31:46 | 6 min 8 s |
| Endpoint Burnside counts, generation and replay | 08:31:46 | 08:33:39 | 1 min 53 s |
| Third-move inverse generation and after-two count | 08:33:39.568 | 08:33:39.906 | <1 s |
| After-two replay and both second-move inverse branches | 08:33:39.906 | 08:33:57 | 17 s |
| After-one replay, final-root generation and persistence | 08:33:57 | 09:20:13 | 46 min 16 s |
| Final-root unique-class counting | 09:20:13 | 09:36:36 | 16 min 23 s |
| Final-root replay and resource closure | 09:36:36 | 10:11:21 | 34 min 46 s |

Phase durations use the first corresponding event timestamps in
[the checker log](../logs/reference/checker.log) and the
[launcher settings](../logs/reference/checker-settings.txt), rounded to whole
seconds; the subsecond phase is shown explicitly. The launcher's elapsed field
has integer-second resolution. Build time is excluded. Raw settings preserve
the locale's comma as the fractional-second separator in their timestamps.

This run supplies a complete independent regeneration and certificate-replay
record. It did not republish the standalone census reports or run the test
suites required by [RESEARCH_PLAN.md](RESEARCH_PLAN.md); those remain separate
evidence checklist artifacts.

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

The completed separate-machine [checker log](../logs/reference/checker.log),
[runtime settings](../logs/reference/checker-settings.txt),
[resource samples](../logs/reference/checker-resources.log),
[GC log](../logs/reference/checker-gc.log), and
[Bash/JShell launcher snapshot](../logs/reference/checker-launch.sh) are retained
alongside the emission evidence. The settings contain initial `status=running`
and final `status=complete` events; the final status and `exitCode=0` record the
completed run. The launcher is the historical script, including its automatic
documentation append; it is not a replacement for the maintained local launcher.
No build logs are retained here. The certificate database and generated checker
work remain outside Git. For a new reproduction, transfer the closed database,
build the checker independently and run the complete scope with fresh work;
see [VALIDATION.md](VALIDATION.md).

Logs, settings, hashes and emitter checkpoints are execution provenance. The
checker establishes computational coverage by regenerating every obligation and
replaying the actual certificate database; no sidecar or obstruction marker can
replace that work. [RESEARCH_PLAN.md](RESEARCH_PLAN.md) remains the full evidence
checklist for the proof.
