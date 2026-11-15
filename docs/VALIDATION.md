# RTX 5090 / AMD EPYC 7742 validation

The simulation profile and launch commands are in [RTX5090.md](RTX5090.md).
The source specification is [td18-reduction-count.tex](td18-reduction-count.tex).
The source inventory retains 53 root partitions, 74 endpoint annotations,
2 direct root classes after support clearing, and 61,798,462 non-elementary
endpoint obligations. These values must be independently regenerated for the
current run; inventory tables do not supply sorting certificates.

No completed simulation or TD(18) proof is claimed by this document. All exit
artifacts in [RESEARCH_PLAN.md](RESEARCH_PLAN.md) are required.

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
CUDA-dependent tests require the configured RTX 5090 environment.

Checker key regressions compare direct canonical packing with the independent
decode-and-pack path on every normalized-current valid pair through seven symbols, arbitrary
current cycles and domains through the full 32-symbol wire limit. They cover
implicit and explicit fixed points, prefix and padding preservation, thread-local
scratch isolation, and comparison with actual normalized pairs. Certificate-store
regressions require canonical-frame replay for rotated current cycles and reject
malformed or over-maximum witnesses even when the requested budget is larger.
The independent inverse oracles continue to check all gains on both endpoints.

The optional native memory-preflight regression uses the 32-slot, 896 MiB
queue profile. It rejects queue-only and one-byte-short memory reports before
allocation, checks exact allocations and tiny-queue minimums, and rejects
oversized capacities. Build it with `-DTD18_BUILD_NATIVE_TESTS=ON` and run
`ctest --test-dir <build> --output-on-failure` on that environment.

## Run data and complete verification

Versioned count reports are generated under `data/eriksson-root-counts-v1/`,
`data/eriksson-root-counts-checker-v1/`, `data/eriksson-endpoint-counts-v1/`, and
`data/eriksson-endpoint-counts-checker-v1/`. Reports must cover every inventory
row and reject missing, malformed, incompatible or pending data.

The dedicated launchers use `/workspace/td18` by default:

- `certificates/`: intrinsic witnesses replayed at each explicit move budget.
- `work/`: versioned emitter checkpoints and independently regenerated checker sets.
- `logs/`: per-run logs and settings, including revision, JVM options and exit status.
- `build/native-5090/` and `tmp/`: native build and temporary JNI files.

Direct roots require budget 10; endpoints require budget 7. Endpoint misses
generate all three inverse waves for both `(1,2,2)` and `(0,3,2)` paths on
both endpoints. The after-two and after-one budgets are 8 and 9; final roots
require 10. No predicted obstruction list or stricter endpoint 3-norm cutoff
may omit an obligation.

After complete emission, run `bash -ic './tools/run-5090-checker.sh'`. The
checker regenerates every obligation and replays the database. Missing
obstruction markers cannot suppress work. Count summaries, emitter checkpoints,
checksums and sidecar manifests are not proof inputs. Every final root must
have a checked result, and partial runs certify only their stated scope.
