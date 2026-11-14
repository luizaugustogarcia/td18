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
