# Negative sorting searches: local profile, 2026-11-14

The main problem in the sampled negative cases is repeated exploration of the
same states. The rank bound rejects most otherwise permitted moves, but CUDA
enumerates billions of supports across millions of repeated paths before
exhausting the restricted tree. An experimental exact state cache removes much
of this work. These are profiling experiments, not computational proof artifacts
or production performance claims.

## Source and scope

The server and local source both started at commit
`46736069b220d645096840738caeb08bf14e957e`. Read-only inspection used plain SSH
and `bash -ic`; profiling and experiments ran locally, also through `bash -ic`.
Java computations used JDK 21 and JShell. The running server job was left intact.

The active stage was `after-one-search.queue`: 6,451,327 source obligations,
3,509,668 CPU closures, and 2,941,659 GPU submissions. At sampling, 4,243 GPU
closures and 2,876 GPU misses had completed. All 48 configured server slots
were occupied, the pending queue was full at 112/112, and all 80 inverse workers
were waiting for work. Advisory root callers also competed for the GPU queue.
This snapshot identifies GPU search as the immediate limiting stage; it does
not establish a constant completion rate or an ETA.

Samples came from the sealed, versioned GPU handoff under
`emitter/target/full-emission-20261114T202741Z/after-one-search.queue`.
They include the first 128 records, records 7,087–7,214 around the estimated
consumer position, and 512 records spread uniformly over the complete handoff.
Each sampled queue preserves the original format and route header with its
own exact record count. Original source indices are recorded in the local
sampling metadata. Successful benchmark witnesses are replayed.

All 512 spread cases have budget nine and rank eight: 205 have domain 18
and 307 have domain 19. Their algebraic cycle lengths are `(5,13)`, `(7,11)`,
`(9,9)`, `(3,5,11)`, or `(3,7,9)`. Initial fixed points are contracted.
The current-position sample has only `(7,11)` and `(9,9)` states on 18 symbols.
Here rank is `(N - c_odd)/2`, including fixed points in `c_odd`; a zero move
preserves `c_odd` and differs from a move creating zero fixed symbols.

Representative records use beta `(0,...,17)` and budget nine:

| Source record | Packed omega key | Cycle lengths | Full-search outcome |
| --- | --- | --- | --- |
| 7,088 | `088e900c918190e52d856bc0` | `(7,11)` | diameter closure |
| 7,093 | `088e9022032162652d8d73c0` | `(9,9)` | restricted-tree miss |
| 7,094 | `088e90422a1910532d8d73c0` | `(9,9)` | restricted-tree miss |
| 7,096 | `088e92808381a2852d8d73c0` | `(9,9)` | restricted-tree miss |

## NVIDIA measurements

Local hardware was an RTX 4070 Laptop GPU, CC 8.9, in WSL, with CUDA 13.5,
Nsight Systems 2026.5.2 and Nsight Compute 2026.4.0. Native code was compiled
for `sm_89` with optimization and line information. Benchmark settings were
16 slots, 128 MiB of queues per slot and 22 host workers. No queue overflow
occurred. This smaller local queue allocation is not a recommendation to change
the server's 16 slots and 896 MiB per slot.

Nsight Systems traced all 16 current-position cases at full depth nine without
a timeout. The persistent search kernels account for essentially all device
execution time: their summed duration is 103.14 seconds, versus 1.23 ms for
initialization kernels and 0.18 ms for transfers. Summed concurrent kernel time
is not elapsed time; this run took 26.43 seconds. Some successful kernels took
only 0.55–2.85 ms. Long host latencies for those cases include scheduling and
completion delays and must not be interpreted as their own search cost.

For the confirmed miss at source record 7,093, Nsight Compute measured:

- 13.65 seconds in a 36-block, 32-thread/block search kernel;
- 98 registers/thread, no compiler-reported spills, and 33.33% theoretical
  occupancy, limited by registers;
- 2.08% achieved occupancy when profiling one case with its allocation of
  1/16 of the resident blocks. This is not aggregate occupancy with 16 active
  searches;
- negligible DRAM throughput. Queue capacity or PCIe bandwidth is not the
  bottleneck for this sample.

A separate hardware-counter run gave the same case all resident blocks using
one slot. It measured four active warps per scheduler, 0.58 eligible warps per
scheduler, 57.10% of cycles with no eligible warp, and only 9.36 active lanes
per warp on average. This supports addressing serial work and divergent support
processing after reducing the amount of search. Source-instrumented profiler
passes became impractically slow and were interrupted; no source-level stall
attribution is claimed. NVIDIA describes these metrics and replay overhead in
its [profiling guide](https://docs.nvidia.com/nsight-compute/ProfilingGuide/index.html).

## What the negative search does

An instrumented copy of the original kernel fully exhausted record 7,093:

| Counter | Count |
| --- | ---: |
| Nodes removed from queues | 45,573,298 |
| Candidate supports | 3,850,998,071 |
| Supports permitted by the move restrictions before the rank test | 1,320,873,007 |
| Those supports rejected by the remaining-budget rank test | 1,275,300,147 |
| Nodes with no zero-move allowance left | 45,572,901 |
| Depth-seven nodes, generating no further feasible children | 19,416,009 |

The bound works: it rejects 96.55% of otherwise permitted supports. It does not
prevent generating those supports or returning to identical states through
different paths. Diameter closures are checked on parents and generated children
using the full budget minus the moves already spent. The negative case is
expensive because no such exit is reached.

## Consequences of the Mühle–Nadeau paper

[Mühle and Nadeau](https://www.numdam.org/item/10.5802/alco.83.pdf),
Proposition 3.1, identifies rank with the minimum number of unrestricted
algebraic 3-cycles. This is a lower bound for sorting transpositions, which must
also respect the current cycle's cyclic support order. It does not compute the
sorting distance of a particular cyclic-target pair.

Corollary 3.3 and Remark 3.5 show that a rank-decreasing continuation from an
all-odd state stays all odd and splits one odd-length cycle into three
odd-length cycles. Proposition 3.6 describes the product structure of the
corresponding intervals. The useful algorithmic distinction is between paths
in these intervals and their distinct states.

Two experiments follow from this structure:

1. **Remember states.** For fixed target rho, omega determines the current
   beta as a cycle, up to its starting position. Record the complete omega key
   and the least depth reached. Revisiting it with no more remaining budget
   cannot add a continuation. Retain actual cyclic support legality and fixed
   symbol restrictions. This is internal search deduplication, not a change to
   candidate-orbit or certificate-class keys.
2. **Generate the possible splits directly.** When no zero-move allowance
   remains and every cycle has odd length, choose three cyclic ranks in one
   cycle whose three gaps are odd. Then test their cyclic order in beta.
   Other states retain general enumeration, including states with even-length
   cycles. The algebraic product decomposition alone does not justify sorting
   separate cycles independently of beta.

A CPU graph experiment exhausted the same negative case after 203,018 distinct
states, testing 65,060,720 supports with ordinary enumeration. Direct split
generation reduced that to 1,779,437 supports. Both versions produced exactly
the same visited-state set. All nine negative cases in the 16-case sample
exhausted with matching state sets, ranging from 176,361 to 203,018 states.
These CPU timings are not GPU speedup measurements.

An independent small-domain check enumerated all 46,232 normalized valid pairs
on three through nine symbols. For all 26,045 pairs with only odd-length
cycles, direct generation matched the complete brute-force sets of
rank-decreasing supports exactly: 246,346 supports. Fixed symbols were excluded
from supports while retaining their original beta positions.

## CUDA cache and timeout experiments

The isolated CUDA prototype adds a bounded, per-search cache with full
95-bit omega keys and least visited depths. Hash collisions compare complete
keys; an unsuccessful bounded probe falls back to ordinary exploration. Entries
are synchronized, and the cache is cleared between inputs. It allocates 12 MiB
per slot in addition to the original queues. It does not yet implement direct
split generation.

For the 16-case current-position sample, the original kernel returned seven
closures and nine restricted-tree misses. Three cache rounds returned the same
per-record dispositions and replayed every successful witness. The observed
cache round times were 0.435, 0.368 and 0.368 seconds. Three corresponding
original rounds took 37.56, 27.41 and 27.57 seconds. Median elapsed time improved
from 27.57 to 0.368 seconds, a factor of 74.85 on this local sample. All rounds
used no warmup, no timeout and the same inputs and host/queue settings.

The cache build also uses 96 registers/thread instead of 98. Nsight Compute
reports a resulting theoretical occupancy increase from 33.33% to 41.67%; the
allocator assigns 45 blocks per case instead of 36 at 16 slots. This contributes
to the implementation comparison and is not held constant. The profiled
negative kernel falls from 13.65 seconds to 176.40 ms. Nsight Systems records
1.66 seconds of summed persistent-kernel time across the 16 cache cases, versus
103.14 seconds originally; clearing the caches costs 0.95 ms in total.

A separate 32-case spread sample contains 13 domain-18 and 19 domain-19 cases.
The original took 9.555 seconds. The cache took 0.339, 0.222 and 0.218 seconds,
with identical per-record outcomes: 20 closures and 12 misses. This comparison
has only one original baseline round. These measurements establish a promising
local result, not a P100 throughput prediction.

Three full-depth cache rounds over all 512 spread cases took 1.975, 1.964 and
1.914 seconds, each returning 333 closures and 179 restricted-tree misses.
Every successful witness replayed, all rounds agreed per record, and the
32-case subset agreed with the original uncapped search. The other 480 spread
cases were not run to exhaustion with the original kernel.

The existing 15 CUDA test methods passed against the cache library through
JShell. They include independent small-domain searches, concurrent dispatch,
rotated beta, exploration caps, timeouts, reuse, and certificate persistence.
At the time of profiling, the cache remained an experimental library under
`emitter/target/native-memo`. On 2026-11-15 it was integrated into the production
source with explicit dominance documentation and two 64-bit key fields retaining
all 95 omega bits. The user requested source synchronization, a remote rebuild
and reconstruction restart without running tests. The historical tests above
apply to the profiled prototype; no new test execution or P100 speedup is claimed
for that deployment.

With the original kernel, full-depth nine and a 100 ms device allowance, the
same 16 cases took about 0.20 seconds after the first round. Six closed and ten
timed out: one case solved by unrestricted-duration search now required inverse
reconstruction. Across 512 spread cases, three rounds took 3.04–3.15 seconds,
closing 299–300 and timing out on 212–213. Every successful witness replayed.
This is a useful scheduling option, but extra inverse generation, persistence
and final-root sorting must be included in an end-to-end comparison. Device
allowances also do not bound host queueing latency. Final mandatory budget-ten
searches retain full depth and no timeout.

## Reproduction and documentation mismatch

Generated inputs, scripts, profiles, logs and prototype native sources are
untracked under `logs/cuda-profile-20261114/`; compiled libraries are under
`emitter/target/`. The directory contains `samples.json`, `sample-summary.txt`,
`profile-counts.txt`, `memo-compare.log`, `memo-cuda-tests.log`, baseline/cache
benchmark logs, and the `.nsys-rep` and `.ncu-repz` reports.
`comparison.json` records the checked per-record comparisons and timings;
`experimental-memo.patch` contains the isolated CUDA change. The sampling
metadata and hashes identify experiments only; they are not proof inputs.

Load `.bashrc` for every invocation. For example, from the repository root:

```bash
bash -ic 'bash logs/cuda-profile-20261114/run_benchmark.sh'
bash -ic 'PROFILE_NATIVE="$PWD/emitter/target/native-memo" bash logs/cuda-profile-20261114/run_benchmark.sh'
bash -ic 'PROFILE_MILLIS=100 bash logs/cuda-profile-20261114/run_benchmark.sh'
bash -ic 'nsys stats --report cuda_gpu_kern_gb_sum,cuda_api_sum logs/cuda-profile-20261114/full16.nsys-rep'
bash -ic '/usr/local/cuda/bin/ncu --import logs/cuda-profile-20261114/miss6-basic.ncu-repz --page details'
```

The paper still described a default intermediate exploration cap of five;
the current Java implementation and architecture documentation use full budgets
eight and nine. This mismatch was identified during profiling and the default
description in the paper was corrected. The route, closure budgets and treatment
of unresolved searches are unchanged. Neither a restricted-tree miss nor a
timeout proves a lower bound or discharges a proof obligation.
