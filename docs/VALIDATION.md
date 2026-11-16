# Validation of the TD(18) proof and reference simulation

The reference configuration, measured timings and retained logs are in
[REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md). The local reference emitter
completed successfully on 16 November 2026 at 07:35:21 Lisbon time, after
4 h 37 min 49 s. The independent reference checker completed on a separate
AMD Ryzen 7 8840HS machine at 10:11:21 Lisbon time the same day, after
1 h 45 min 43 s, with exit 0 and complete regenerated coverage.
The source specification is [td18-reduction-count.tex](td18-reduction-count.tex).
The source inventory retains 53 root partitions, 74 endpoint annotations,
2 direct root classes after support clearing, and 61,798,462 non-elementary
endpoint obligations. These values must be independently regenerated for the
current run; inventory tables do not supply sorting certificates.

A full independent checker run has already succeeded. This reference reproduction
has a closed certificate database and complete emission and separate-machine
verification logs under `logs/reference/`. The checker verified 1,349,910,890
final reconstructed root classes. Tests were skipped for that run at the
operator's request; it did not republish standalone census reports. Those
remain distinct requirements in the evidence checklist. The proof evidence is
specified in [RESEARCH_PLAN.md](RESEARCH_PLAN.md).

## Build, test and regenerate inventories

Configure JDK 21 and the CUDA toolkit in `~/.bashrc`. Run from the repository:

```bash
bash -ic './tools/td18 build'
bash -ic './tools/td18 test'
bash -ic './tools/td18 inventory'
bash -ic './tools/td18 count-roots'
bash -ic './tools/td18 audit-roots'
bash -ic './tools/td18 count-endpoints'
bash -ic './tools/td18 audit-endpoints'
```

Java entry points use JShell and all allocated CPU cores. Both required CPU
suites must pass. Small-domain tests compare complete forward class sets,
inverse gains zero through three on both endpoints, and Burnside counts with
direct generation. The checker builds independently of the emitter artifact.
CUDA-dependent tests require a configured CUDA environment. The local SM 89
build passed the native memory preflight and a three-symbol GPU sorting/replay
smoke check before emission. CPU-only checker execution does not require CUDA.

Checker key regressions compare direct canonical packing with the independent
decode-and-pack path on every normalized-current valid pair through seven symbols, arbitrary
current cycles and domains through the full 32-symbol wire limit. They cover
implicit and explicit fixed points, prefix and padding preservation, thread-local
scratch isolation, and comparison with actual normalized pairs. Certificate-store
regressions require canonical-frame replay for rotated current cycles and reject
malformed or over-maximum witnesses even when the requested budget is larger.
The independent inverse oracles continue to check all gains on both endpoints.

The optional native memory-preflight regression checks the local 8-slot,
768 MiB allocation and retains the larger 32-slot, 896 MiB case as a memory
budget regression. It rejects queue-only and one-byte-short memory reports
before allocation, checks exact allocations and tiny-queue minimums, and
rejects oversized capacities. Large-budget rejection checks do not allocate
those queues. In the configured local CUDA environment, run:

```bash
bash -ic 'TD18_BUILD_NATIVE_TESTS=ON ./tools/run-local-emitter.sh --build'
bash -ic 'ctest --test-dir emitter/target/native --output-on-failure'
```

## Run data and complete verification

Versioned count reports are generated under `data/eriksson-root-counts-v1/`,
`data/eriksson-root-counts-checker-v1/`, `data/eriksson-endpoint-counts-v1/`, and
`data/eriksson-endpoint-counts-checker-v1/`. Reports must cover every inventory
row and reject missing, malformed, incompatible or pending data.

The reference artifacts use repository-relative paths:

- `certificates/`: the closed reference database, transferred for independent replay.
- `emitter/target/local-emission-20261116T025732Z-46940/obligations/`: untracked emitter checkpoints and work sets.
- `logs/reference/`: complete emission and checker logs, runtime settings, launcher snapshots and reference summary; no build logs.
- `checker/target/reference-verification-20261116T082538Z/obligations/`: untracked fresh independent checker work for the completed verification.

Other simulation logs, databases and work sets have been removed from this local
checkout.

Direct roots require budget 10; endpoints require budget 7. Endpoint misses
generate all three inverse waves for both `(1,2,2)` and `(0,3,2)` paths on
both endpoints. The after-two and after-one budgets are 8 and 9; final roots
require 10. No predicted obstruction list or stricter endpoint 3-norm cutoff
may omit an obligation.

## Separate-machine verification

The completed verification used checker revision
`5871a309f170d45cbd84d268f41952fb7815c667`, with 16 CPU workers and a 6 GiB
maximum heap. Its checker Java sources are unchanged from emission revision
`3ee6d121987c734a01872461ae8b0fc92c54fb4a`. The exact historical Bash/JShell
command is retained in [checker-launch.sh](../logs/reference/checker-launch.sh),
and the complete result and phase timings are in
[REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md).

To reproduce the check, transfer the closed `certificates/` directory and use
the recorded checker revision, or record and audit any source changes. The
native compatibility patch changes only the GPU build configuration and is
not a checker dependency. Configure JDK 21 in `~/.bashrc`. From the repository
on the verifying machine:

```bash
bash -ic 'mvn -q -pl checker -am compile dependency:build-classpath -Dmdep.outputFile=target/jshell-classpath'
bash -ic '
  set -o pipefail
  td18_verify_stamp=$(date -u +%Y%m%dT%H%M%SZ)
  td18_verify_log="$PWD/logs/reference/checker-$td18_verify_stamp.log"
  mkdir -p logs/reference
  ./tools/td18 verify --phase=all --database="$PWD/certificates" \
    --work="$PWD/checker/target/reference-verification-$td18_verify_stamp" \
    2>&1 | tee "$td18_verify_log"
'
```

The checker uses all JVM-visible CPU cores by default. Set
`TD18_JSHELL_OPTIONS` for an appropriate heap and bounded persistence on that
machine; record those actual options, CPU affinity, RAM, OS and JDK version,
source revision, start/finish timestamps and process exit code alongside the log.
These commands run the verification; the executing user adds the finished log
and configuration to `logs/reference/` after completion. The log must contain
`All regenerated obligations in the complete joint-selection scope are verified.`
and the process must exit 0. Update the reference record with that run's measured
duration and regenerated counts; a successful partial phase is insufficient.

The checker regenerates every obligation and replays the database. Missing
obstruction markers cannot suppress work. Count summaries, emitter checkpoints,
checksums and sidecar manifests are not proof inputs. Every final root must
have a checked result, and partial runs certify only their stated scope.
