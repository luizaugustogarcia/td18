# CUDA throughput measurements

On 2026-11-14 the kernel with zero-move allowance pruning completed a
production sample **about 64 times as fast** as the original kernel on a
Tesla P100-SXM2-16GB: median throughput increased from **25.756 to 1649.462
searches/second** with eight slots. Implementation optimizations alone had
reached about 66.5 searches/second. This is a sorting benchmark, not an
end-to-end proof run or a prediction for other search domains.

## Workload and validation

The benchmark sampled 512 records at offset zero and stride 5200 from an
immutable snapshot of the running `after-one-search.queue`. The snapshot
contained 2,697,689 records. The sample contained 180 domain-18/budget-9
states and 332 domain-19/budget-9 states. All builds used exploration
depth five, the full closure budget nine, and no timeout. Every round
returned 275 diameter closures and 237 depth-limit results. Every successful
witness was replayed by CertificateValidator.
Every sampled state had only odd-length cycles and 3-norm eight, hence an
allowance of at most one zero move within budget nine. Here a zero move
preserves the odd-length-cycle count, not the fixed-symbol count.

The td18-gpu-benchmark-v1 input fingerprint was
58e948233d651cb80f7e9ab90db7d254735f8ebe57c52e1cacb42f0307af38ff.
It identifies this experiment; it is not a proof input.

CUDA 11.3.109 compiled native sm_60 code in Release mode, running with
driver 465.19.01 and JDK 21.0.8. Measurements used GPU 0 while production
continued on GPUs 1–3. Timing includes dispatch and witness replay but
excludes input loading and warmup. The final comparison used 32 host workers,
eight slots, 128 MiB/slot, 128 warmup cases and five measured rounds:

| Build | Median searches/second |
| --- | ---: |
| Implementation optimizations only | 66.559 |
| With zero-move allowance pruning | 1649.462 |

An earlier independent five-round run of the latter measured 1655.307/s.
All 5,120 per-case outcomes across the two runs matched the original
baseline: 275 closures and 237 unresolved cases in every round, with no
timeout or overflow. Every successful witness replayed.

The following slot sweep preceded the zero-move pruning change. It used
three measured rounds after warmup and shows the implementation-only build:

| Kernel | Slots | Queue MiB/slot | Median searches/second |
| --- | ---: | ---: | ---: |
| Previous | 8 | 128 | 25.756 |
| Optimized | 2 | 128 | 63.365 |
| Optimized | 4 | 128 | 66.198 |
| Optimized | 8 | 128 | 66.476 |
| Optimized | 16 | 64 | 60.580 |
| Optimized | 32 | 64 | 52.454 |
| Optimized | 64 | 64 | 44.200 |

The 2/4/8-slot measurements used 32 host workers; the 16/32/64-slot
measurements used 128. Every optimized per-record disposition matched
the corresponding baseline disposition: 9,216 comparisons across six
configurations and three rounds. No timeout or queue overflow occurred.
Increasing slots reduced the GPU parallelism available to each case and
did not improve throughput in that sweep. The final build was measured at
the existing eight-slot setting; no claim of its optimal slot count follows.
These samples do not establish a smaller queue-memory requirement for every
final-root search.

Both required Maven CPU suites passed. The 15 CUDA test methods in
GPUSortingSearchSmokeTest, GpuDiameterClosureTest, GpuSearchTimeoutTest,
GpuExplorationCapTest and GpuReconstructionSearchTest passed against the
final P100 library through JShell. These include all valid pairs through
six symbols, 120 independent capped-search comparisons on seven to nine
symbols, timeout/slot reuse, full-budget closures at an exploration cap,
and certificate persistence. The added 19-symbol test checks the all-odd
rejection, equality at the necessary closure depth, and closure-disabled
identity searches. The source paper compiles with the new pruning proof.

## Changes

The main improvement combines the zero-move allowance with the all-odd
geodesic structure in Mühle and Nadeau,
[Proposition 3.1 and Corollary 3.3](https://doi.org/10.5802/alco.83).
Once the remaining full budget equals the current 3-norm, every potentially
successful continuation must decrease rank at every move. If all cycles
have odd length, that property persists. A nonidentity all-odd remainder
of rank r has at least 2r+1 nonfixed symbols; the actual diameter table then
determines the ranks at which it can close. Subtrees whose exploration cap
cannot reach such a rank are rejected as unresolved. The capped all-odd
lemma in [the source paper](td18-reduction-count.tex) proves this rule.
It does not affect full-depth final-root searches.

The kernel shares one parent state and move history per warp, avoiding
per-thread local arrays. Stop/completion polling uses relaxed loads instead
of atomic read-modify-write operations. Queue indexing uses conditional
wrapping instead of integer division. Child batches are copied by the whole
warp while the owner retains the queue lock; each copying lane fences its
writes before publication.

At the exploration cap, the existing consecutive-support scan covers every
move gaining at least two fixed symbols. When the actual diameter table
rules out a closure from gains zero and one, the kernel skips the remaining
general supports because no child can be expanded. Cycle metadata is built
only when general enumeration is needed.

The search restrictions, depth limits, timeout rules, full obligation
budgets, work accounting and unresolved-result handling are preserved.
The kernel still explores the restricted sorting tree documented in
[the architecture](ARCHITECTURE.md); a miss does not establish a lower bound.

## Inverse reconstruction and persistence

After activating the CUDA change, the live reconstruction still dispatched
only about 50 searches/second across three GPUs, with roughly 1.5% slot
occupancy. Three thread snapshots found 97--100 of 112 reconstruction
consumers blocked on individual `DiskStateSet.add` monitor acquisitions.
None caught a disk flush. Each consumer performs inverse generation after
a GPU miss before requesting another search.

Inverse callbacks now collect up to 256 canonicalized keys outside that
monitor before inserting them together. Final partial batches are inserted
before return, and a failed generation prevents sealing. The key format,
deduplication, budgets and per-key disk flush threshold are unchanged.

A separate JShell benchmark used the 237 depth-limit cases from the CUDA
sample. With 112 workers on the server, three alternating-order trials
measured 150--161 inverse inputs/second with individual inserts and
247--251 with batching, about 1.6 times faster during generation. Including
sealing, the aggregate improvement was about 10%. The production job
continued during this experiment, so these are shared-server measurements.
Every trial generated 512,680 parents and sealed exactly 473,336 root keys;
every key and value matched byte-for-byte. All 237 sources had partial
batches. Both required Maven suites passed after the change, including
concurrent insertion, flush-boundary, partial-batch, reopen and failure tests.

The script `tools/audit/BenchmarkInverseBatching.jsh` reproduces this
comparison with `td18.inverseBenchmarkQueue` set to an immutable after-one
sample. `td18.inverseBenchmarkCases`, `Workers`, `Rounds` and `Warmup`
(each with the same prefix) control the run. Output databases are temporary
benchmark artifacts. This CPU result and the CUDA result measure different
parts of the job; their speedup factors must not be multiplied.

## Removing the global producer lock

The historical 256-key batching measurements above correspond to commit
970dc43. Subsequent live snapshots still found 84 of 112 inverse workers
blocked on its shared insertion monitor, with an empty native GPU queue.

The current implementation sends 65,536-key producer batches to independent
writer shards through lock-free queues. Each writer owns its deduplication
buffer and sorted SST files; semaphores impose bounded queue backpressure.
There is no global producer insertion lock. The 128 GiB server uses eight
writers, a 67,108,864-key aggregate deduplication window and 4,096 queued
batches. SST construction runs independently in each shard, bypassing the
database WAL/memtable write path. Ingestion enables global sequence numbers
so repeated empty-valued keys remain one set entry. Up to sixteen background
jobs and eight subcompactions handle subsequent compaction. Queues still
apply backpressure when persistence cannot keep up; larger buffers cannot
remove a sustained storage limit.

Sealing rejects active generators and drains and joins every writer before
flushing and validating the persisted inventory. Writer failures propagate
to producers waiting for queue space and prevent sealing incomplete output.
Correctness tests cover concurrent duplicate insertion, writer layouts,
full queues, final partial batches, failed writes, and drain/join boundaries.
This revision has no throughput benchmark, as requested; no new speedup
factor is claimed.

## Independent GPU dispatch and inverse work

After installing writer shards, a live idle-period snapshot found all 112 GPU
dispatch workers waiting for space in a persistence queue. GPU misses still
executed their entire inverse callback synchronously. Three active writers
were in RocksDB writes, and later partitions of producer batches could be
held behind a full earlier shard.

GPU misses now enter a separate versioned disk queue, drained by an independent
CPU inverse pool. GPU dispatch neither generates parents nor waits for their
persistence queues. Producers offer all shard partitions before waiting on
any full destination. Both GPU and inverse handoffs must drain, all worker
pools must join, and all three coverage equalities must hold before sealing.

Logs report processed/total, percentage, remaining, cpuRemaining, gpuRemaining
and inverseRemaining. gpuSubmitted is explicitly cumulative. Processed includes
completed inverse callbacks; final persistence draining and inventory validation
remain sealing obligations. This makes GPU completion visible even while
inverse generation continues. Storage still bounds the overall reconstruction
rate. No throughput benchmark was requested for this change.

## Reproducing a comparison

Build Java with tools/td18 build and prepare separate native-library
directories for each kernel. Copy a completed handoff file to an immutable
snapshot. Run each library in a fresh JShell process:

```bash
jshell --feedback concise \
  --class-path "emitter/target/classes:$(cat emitter/target/jshell-classpath)" \
  -R-Djava.library.path=/path/to/native-build \
  -R-Dtd18.gpuDevices=0 -R-Dtd18.gpuSlots=8 -R-Dtd18.gpuBudgetMB=128 \
  -R-Dtd18.benchmarkWorkers=32 \
  -R-Dtd18.benchmarkQueue=/tmp/queue-snapshot.bin \
  -R-Dtd18.benchmarkCases=512 -R-Dtd18.benchmarkStride=5200 \
  -R-Dtd18.benchmarkRounds=5 -R-Dtd18.benchmarkWarmup=128 \
  -R-Dtd18.benchmarkDetails=true \
  tools/audit/BenchmarkGpuSearch.jsh
```

The script checks the snapshot header, record size/count, selected keys,
padding, cyclic-target validity and fixed-point contraction. It requires
every requested sample record and replays all successful witnesses.
td18.benchmarkValidateOnly=true checks input without loading CUDA. Without
a queue it generates deterministic fixed-point-free valid pairs; these
are a separate workload. Budget-10 cases always use full depth ten without
a timeout. Generated snapshots, logs and compiled libraries stay untracked.
