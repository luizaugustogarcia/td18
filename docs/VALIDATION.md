# Checked active route on 2026-11-13

This is a completed route migration and CPU validation, not a completed
TD(18) proof. Endpoint sorting and final inverse coverage remain pending.

The source specification is [td18-reduction-count.tex](td18-reduction-count.tex).
Java computations ran through JShell on JDK 21, using all 16 JVM-visible CPU
cores. Counts use runtime worker detection, without a machine-specific default.
The checker builds independently and has no emitter artifact dependency.

| Check | Result |
| --- | --- |
| `mvn -pl emitter -am test` | Passed |
| `mvn -pl checker -am test` | Passed |
| Full-cycle small-domain forward oracle | Complete class sets through 8 symbols agree |
| Independent inverse oracle | All gains 0–3, both endpoints, through 7 symbols agree |
| Async root persistence and three-wave resume regressions | Passed |
| Explicit certificate budgets in both modules | Passed; longer valid results are misses at smaller budgets |
| Emitter and checker endpoint census | All 74 rows agree; 76,048,061 classes, 61,798,462 requiring discharge |
| JShell driver exit checks | Passed for success, runtime errors, and compilation failure |
| Source paper and reading PDF | Built successfully with resolved references |
| Emitter and independent root census | All 53 rows agree; 2 direct classes after support clearing |
| Direct-root CPU certificates | Both `(11,4,4)` and `(7,6,6)` discharged within 10 |
| Independent direct-root replay | Passed for both complete direct-root partitions, 10 and 29 |
| Full TD(18) proof | Pending endpoint sorting and inverse coverage |

The endpoint domain has 14 symbols and budget 7; the direct root domain
has 19 symbols and budget 10. Both `(1,2,2)` and `(0,3,2)` inverse
patterns participate on both endpoints. Gain-zero first moves are included.
No stricter endpoint 3-norm cutoff or predicted sorting-failure list is assumed.

## Reproduce and inspect artifacts

```bash
tools/td18 build
tools/td18 test
tools/td18 inventory
tools/td18 count-roots
tools/td18 audit-roots
tools/td18 count-endpoints
tools/td18 audit-endpoints
```

The complete root commands validate and resume versioned per-partition reports.
`count-roots` publishes active paper rows only after the complete inventory is
present. A partial source census does not authorize emission or a proof claim.

Generated artifacts stay untracked:

- `data/eriksson-root-counts-v1/` and `data/eriksson-root-counts-checker-v1/`:
  versioned emitter/independent counts, explicit route, domain and budget.
- `data/eriksson-endpoint-counts-v1/` and
  `data/eriksson-endpoint-counts-checker-v1/`: full annotated endpoint census.
- `certificates/`: intrinsic witnesses, replayed at the requested budget.
- `logs/eriksson-route-v1/`: census, tests, certificate and verification logs.

The checker regenerates obligations and replays the database; count summaries,
emitter checkpoints, checksums and sidecar manifests are not proof inputs.
Missing data are errors, and missing obstruction markers cannot omit work.
Partial runs do not discharge the complete route.
CUDA-dependent tests were skipped in this CPU migration validation.

## Targeted follow-up on 2026-11-15

Local review fixes passed 33 emitter avoidance/persistence tests and 15 CUDA
tests through the JUnit launcher in JDK 21 JShell, with 22 available CPU cores.
The resume regression covers all three checkpoint stages: each mandatory scan
processes records and then rejects a mismatched exact count without publishing
the next checkpoint. ROOTS checkpoint timing remains unchanged.

The CUDA memory-preflight regression passed on a configured RTX 4070 Laptop GPU.
It substitutes queue-only and one-byte-short free-memory reports and requires
rejection before any device-buffer allocation. Exact-fit cases compare the
preflight total against actual `cudaMalloc` requests, including tiny-queue
minimums; oversized capacities are rejected. The simulated P100 case keeps
16 slots and 896 MiB queues, without allocating 14 GiB on the local GPU.
Build this optional Linux native test with `-DTD18_BUILD_NATIVE_TESTS=ON` in a
configured CUDA build, then run `ctest --test-dir <build> --output-on-failure`.

Both modules emitted valid millisecond timestamps with timezone offsets at all
five SLF4J levels using their default logger resources. The reading PDF rebuilt
successfully and includes the source paper's cache discussion. Logs and JShell
drivers are untracked under `logs/review-fixes-*`; the native build is under
`emitter/target/native-review/`. These targeted checks do not complete the
required full proof verification. The running P100 simulation was not modified.

The CPU root helper is `tools/audit/DischargeErikssonRootsCpu.jsh`; it uses
all available processors, replays each found result, and verifies persistence.
It defaults to at most 100 direct classes, with `td18.cpuRootAuditMaxClasses`
controlling that bound; larger inventories use the bounded production emitter.
Run it through JShell with the prepared emitter classpath. For full proof replay:

```bash
tools/td18 verify --phase=all
```

## Cleanup validation on 2026-11-13

The superseded selectors, inventories, inverse waves, launch entries, census
publishers, prototype comparisons, historical appendix and counting reports
have been removed. The emitter now owns a single active root generator; shared
move operations and the checker's budgeted search state have independent,
route-neutral classes. Existing intrinsic certificate wire formats and checked
witnesses remain replayable.

Both required CPU suites passed from cleaned module outputs. The inventory
checks cover missing, duplicate, reordered, malformed and pending rows. Current
endpoint budgets round-trip through the checker's disk set; no former phase
minimum is inferred from a state domain. Empty-database tests reject missing
root certificates and regenerate endpoint obligations without stored markers.
The checker remains independent of the emitter artifact.

Both direct-root partitions were independently regenerated and their certificates
replayed after cleanup. The source generator also rechecked both direct classes
and the support-cleared partition. Endpoint sorting and final coverage remain pending.

## Endpoint tables checked on 2026-11-14

The complete endpoint table is embedded in the source paper and reading PDF,
including zero-count annotations, move budgets, 3-norms, total class counts and
non-elementary discharge obligations. Every table row matches both versioned
census report sets. Root inventory totals are printed as well. PDF text and
layout were checked after rebuilding; both required CPU suites passed.
Reproduce the table using `tools/td18 endpoint-table` after the two endpoint
censuses. The checked tables are summaries; proof verification still regenerates
obligations and replays certificates independently.

## Checker canonicalization optimization on 2026-11-15

JDK 21 CPU suites passed: 87 checker tests and 157 emitter tests, with the 15
CUDA-dependent emitter tests skipped. The new checker tests compare the exact
canonical word with the preserved full-word orbit minimum for every permutation
through seven symbols, including implicit fixed points, mixed equal-size
annotations, all images of a twisted-reflection example, and 1,000 deterministic
random cases on 14, 16, 18, 19 and 23 symbols with arbitrary current cycles.

`tools/audit/BenchmarkCheckerCanonicalization.jsh` compares the previous orbit
minimum with the optimized implementation in the same JDK 21 JShell process.
It uses all available CPUs, warms both implementations, alternates their order,
and checks every per-source multiset of encoded inverse parents, including
duplicates. Its versioned input format rejects incomplete inventories and
requires fixed-point-free valid pairs in both after-one domains, with budget 9.
Fixed points must be contracted before using this inverse benchmark.

The local 22-CPU experiment used 32 deterministic random source pairs on each
of 18 and 19 symbols, covering inverse gains one and zero on both endpoints.
Every trial generated the same 217,048 parent keys. Across three measured rounds,
median elapsed time fell from 0.949855 s to 0.219361 s: 228,506 to 989,455
generated parents/s, a **4.3301x speedup (+333.01% throughput, 76.91% less elapsed
time)**. Average CPU use was approximately 19 cores in both modes.

The timed scope includes inverse generation, canonicalization, normalization,
encoding and per-source sorting. It excludes certificate lookups, disk persistence
and complete proof verification; this is a performance experiment, not a proof
artifact or a whole-checker speedup measurement. The input, JShell driver and
results remain untracked under `logs/canonicalization-20261115/`. The additional
server benchmark was cancelled at the user's request. The changed checker must
start with fresh work and regenerate every obligation.
