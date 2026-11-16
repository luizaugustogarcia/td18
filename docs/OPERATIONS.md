# Runtime operations

The independent checker owns its scratch storage implementation. Inverse
producers retain bounded per-thread key batches and distribute them across
deterministic persistence shards. Each shard deduplicates and sorts its own
window, builds an SST, and ingests it without WAL. All producer remainders and
writers drain before sealing. Parallel read batches decode and replay complete
lookup responses before processing misses; iterator errors, count mismatches,
malformed records and asynchronous failures abort verification. The checker
continues to regenerate its own obligations and never resumes emitter scratch
sets. Certificate databases remain read-only. Scratch sizing uses
`td18.reconstructionGeneratedBatchKeys`, `td18.reconstructionWriters`,
`td18.reconstructionQueueBatches`, `td18.reconstructionBufferKeys`, and
`td18.reconstructionScanBatchKeys`, with the same bounded batching approach as
the emitter. A changed checker must restart with fresh work.

The checker packs canonical keys directly for certificate lookups and for
residual and inverse-state writes. This applies to roots, endpoints, all three
inverse waves and final-root lookups. Certificate hits retain full witness replay;
one replay-derived budget supplies both the project-maximum check and the
requested-budget comparison. Normalized inputs are reused for replay only after
an exact comparison with the computed canonical key. The version-2 key bytes and
certificate schema are unchanged. These changes reduce CPU work; counting,
generation coverage, disk I/O and compaction obligations remain as specified.

The default commands use the active route in
[the source paper](td18-reduction-count.tex).

## JShell, CPU and GPU configuration

`tools/td18 build` compiles both modules and prepares their dependency
classpaths. Every prepared Java entry point runs through JShell. CPU counts use
all JVM-visible processors, bounded generation queues and disjoint rank-placement
prefix tasks. `tools/td18 test` runs both required Maven CPU suites.
CUDA-dependent tests run only in a configured environment.

SLF4J logs in both modules default to `showDateTime=true`, with timestamps in
`yyyy-MM-dd'T'HH:mm:ss.SSSXXX` format (milliseconds and timezone offset).
The classpath `simplelogger.properties` files apply to JShell and packaged runs.

Set `TD18_JSHELL_OPTIONS` for execution-VM options, for example
`-R-Xmx3g` or `-R-Dtd18.twistedBraceletWorkers=auto`. The launcher passes
`-R-Dtd18.countModule=emitter|checker` to keep report ownership separate.
Use `tools/td18 build-gpu` for native CUDA libraries. GPU sorting is entered
only after applicable CPU prefixes and known-diameter checks fail.

Direct root and endpoint emission use `WitnessSearch.searchFresh`: their
generators already emit one representative per valid-pair class within each
type, so sorting begins with the CPU prefix without a preliminary certificate
database lookup. Source imports and avoidance miss regeneration retain
stored-certificate lookups. Reconstruction replays stored certificates with
one bounded `multiGet` per source batch before dispatch; its CPU and GPU miss
paths do not repeat individual `get` calls. Root prefetch also uses bulk
lookup followed by searches of prepared misses. Every new result still passes
certificate validation before persistence.

Concurrent reconstruction scans copy encoded RocksDB keys into bounded batches.
Workers decode each batch, perform its certificate lookup and replay, then search
its misses directly. This removes serial decoding/replay and per-class task
submission from the iterator thread. At most the JVM's available CPU count runs
these batches; the bounded task queue applies backpressure. The batch size is
`min(td18.reconstructionScanBatchKeys, td18.certificateLookupBatchSize)`, with
defaults 4,096 and 262,144. Checkpoint inventory validation uses the same parallel
decoding. Iterator errors, malformed records, incomplete lookup responses and
worker failures abort the scan; an exact count is published only after every
batch, including its final remainder, joins successfully.
Resuming any sealed-wave checkpoint combines its validation with this mandatory
processing scan. The checkpoint count is an expectation for progress and final
coverage checks, never a completed inventory until the scan succeeds. A failed
scan cannot publish the next checkpoint.

GPU properties retain the shared machinery: `td18.gpuDevices`,
`td18.gpuSlots`, `td18.gpuBudgetMB`, `td18.gpuQueueCapacity`,
and `td18.gpuStatsPeriodSeconds`. CPU prefix properties are
`td18.cpuPrefixDepth`, `td18.cpuPrefixMaxTriples`, and
`td18.reconstructionCpuPrefixMillis`. Limits bound a heuristic; they do not
remove the original sorting obligation.

The [reference profile](REFERENCE_SIMULATION.md) uses an Intel Core Ultra 9
185H, 22 CPU workers, a 16 GiB Java heap, and an RTX 4070 Laptop GPU with
8 slots and 768 MiB of queue memory per slot. It emits the complete scope
through JShell into `certificates/`. Its exact launcher and settings are retained
with its completed logs in `logs/reference/`. Independent verification runs on
a separate machine with fresh checker work. The [RTX 5090 / AMD EPYC 7742
profile](RTX5090.md) remains available with 32 slots and 896 MiB per slot.
All RocksDB writes disable
WAL; completed phases explicitly flush certificates. An interruption may lose
unflushed results, which must be regenerated.

Reconstruction runs CPU searches, GPU dispatch and inverse generation in separate worker pools.
CPU workers scan the sealed source wave without waiting for GPU capacity;
only CPU misses enter an append-only disk queue. GPU workers drain that queue
and send every exhausted, depth-limited or timed-out class to a second
disk-backed queue. Dedicated CPU inverse workers reconstruct its parents.
GPU dispatch never waits for inverse generation or its output persistence. The CPU
prefix depth defaults to 5, with at most 8192 support checks and a 10 ms time
allowance per class. `td18.cpuPrefixDepth` overrides the depth; outside
reconstruction its default remains 3. The support and time properties retain
their configured values. A CPU miss sends the original class and full budget
to CUDA. CUDA returns as soon as a generated prefix reaches identity or the
known diameter of its fixed-point-contracted remainder fits the remaining
budget, including during first-move seeding.

`td18.reconstructionGpuMillis` gives each intermediate reconstruction search
an optional device time allowance, defaulting to 0 (disabled). Intermediate
searches explore their full move budgets (8 or 9); the old depth-five cap and
`td18.reconstructionGpuDepth` override have been removed. Budget-10 reconstruction searches always
use full depth 10 without a time cutoff.
Ordinary root and endpoint emission searches retain their existing behavior.
The timer starts during first-move initialization on the device, includes device
scheduling from that point, and excludes waiting in the host dispatch queue.
CUDA checks it cooperatively between nodes and support batches, so the allowance
is not a hard deadline for the host call to return. A timeout produces no
certificate and leaves the class unresolved. All inverse parents are still
enumerated; an unresolved final root remains an error. The obligation budget
continues to govern admissible pruning and diameter closures. A depth-limited
search returns an explicit unresolved status without moves. GPU statistics
count depth-limited results among misses; timeouts remain a separate count.

CUDA explores a restricted sorting tree: supports avoid fixed symbols, the
number of odd-length cycles increases by zero or two, and supports in three
distinct algebraic cycles are excluded. The CPU prefix search does not apply
that last restriction. An exhausted CUDA search therefore supplies no distance
lower bound, even with the time allowance disabled and exploration cap removed.

`td18.reconstructionGpuWorkers` optionally controls the dispatch pool; its
default is twice the total GPU slots plus `td18.gpuQueueCapacity`, covering
active searches and the native dispatcher queue. These workers mostly wait
for GPU results and do not replace the CPU pool.

Log labels identify `Root emission partition`, `Endpoint emission type`,
`Certificate persistence`, `State inventory`, `State persistence`, or
`Inverse reconstruction` explicitly. Root emission reports completed sorting
obligations separately from `generationPrefixNodes` and
`generationStillRunning`; an empty sorting obligation count does not mean
generation has finished. Shared progress reports omit percentages for zero
totals instead of showing `0/0 (100%)` while generation is running.

Progress reports show processed/total, percentage, remaining source cases,
and separate cpuRemaining, gpuRemaining and inverseRemaining counts.
Processed is cached + cpuClosed + gpuClosed + inverseCompleted. A completed
inverse callback means its output has been queued; all writers still drain
before sealing. gpuSubmitted is cumulative and stays fixed after CPU
production ends. gpuMisses counts transfers to the inverse queue immediately.
td18.reconstructionInverseWorkers controls the inverse pool; its default is
the smaller of the CPU producer count and the JVM's available processors.
The native GPU statistics report timeouts separately from exhausted searches;
the reconstruction pipeline's unresolved GPU count includes both outcomes.
A wave completes only after CPU production, GPU dispatch and all inverse work
have joined. Coverage checks require every GPU input to be closed or transferred
and every transferred case to finish inverse processing.

The final root inventory is counted during the mandatory root-processing scan.
Persistence writers drain before that scan starts; there is no preliminary
count-only traversal. Until the iterator completes, progress reports an unknown
total. The iterator validates every record and its status, and processing still
checks all CPU/GPU/inverse coverage equalities. The ROOTS checkpoint is written
with the exact count only after the processing workers drain. An interruption
before that boundary resumes the preceding sealed wave. The independent checker
continues to regenerate and verify its own inventories.

The capped CUDA search combines the zero-move allowance with the invariant
that rank-decreasing moves preserve all-odd cycle structure. Its table-driven
subtree rejection leaves the class unresolved and preserves every inverse
obligation; the source paper proves the rule. A zero move here preserves the
odd-length-cycle count, independently of its fixed-symbol gain.
GPU consumers only dispatch searches and transfer misses. The separate inverse
pool can wait for persistence without preventing further GPU submissions.
td18.reconstructionGeneratedBatchKeys defaults to 65,536 keys.
Inverse generation batches these canonicalized keys into independent writer
queues; deduplication and disk writes have no global producer lock. Every
producer buffer is retained across callbacks and its final partial batch is
queued after all generators join. Every writer drains and joins before sealing.
td18.reconstructionWriters defaults to at most eight writers.
td18.reconstructionBufferKeys defaults to 16,777,216 aggregate keys;
td18.reconstructionQueueBatches defaults to 1,024 aggregate batches.
Both capacities are divided across shards with rounding up. The RTX 5090 /
AMD EPYC 7742 profile uses up to 64 writers, 67,108,864 buffer keys and
32,768 queue batches.
Each writer has a 1,048,576-key buffer budget; the 524,288-entry database
batch limit flushes its distinct keys earlier. Queues remain
bounded, and full queues apply backpressure when storage cannot keep up.
Writers sort their deduplication windows and build SST files independently.
Ingestion bypasses the per-key WAL/memtable write path; overlapping keys
retain the same empty value under global sequence numbers. Up to sixteen
background jobs and eight subcompactions handle subsequent compaction.
DEBUG persistence logs report table construction and completed ingestion with
key counts, bytes and elapsed time. Failed construction or ingestion makes
the wave unsealable; interrupted waves are regenerated from the sealed source.
The inverse completion counter records finished generation and encoding.
Producer remainders and queued tables must still drain before the persisted
inventory is validated and the wave checkpoint is written.
Root GPU prefetch consumes encoded output while reconstruction continues,
starting immediately with one worker per GPU slot. Its bounded RAM queue holds up to
16,777,216 keys in 256 full producer batches; a bounded recent-key cache
suppresses repeated early checks. Queued advisory work can be discarded
when switching to the complete sealed-root scan. Advisory searches use
td18.rootPrefetchGpuMillis (100 ms by default, required positive), at full
depth. Incomplete early searches write no obstruction markers. Ordinary
final-root searches remain full depth with no timeout. All certificate
validation stays in the normal search
path. Early-search counts are scheduling statistics, not final inventory
or proof coverage counts.

## Budgets and persistence

The reference emitter uses `td18.gpuSlots=8` and `td18.gpuBudgetMB=768`,
for 6 GiB of GPU queues plus state caches and metadata. The optional RTX 5090
profile retains 32 slots and 896 MiB per slot, or 28 GiB of queues.
The reference settings in `logs/reference/` record the actual JVM options,
source revision, work path, start/finish times and completion status.

| State | Symbols | Budget |
| --- | ---: | ---: |
| Direct/final root | 19 | 10 |
| Endpoint | 14 | 7 |
| After two moves | 16 | 8 |
| After one move, gain-one branch | 18 | 9 |
| After one move, gain-zero branch | 19 | 9 |

A domain alone no longer determines the obligation budget: 19-symbol states
can occur with budgets 9 and 10. Certificate persistence therefore
validates intrinsic results against the project maximum, and lookups replay them
against the explicit requested budget. A valid longer result is a miss for a
smaller budget. Malformed results are errors. Intrinsic witnesses are independent of the route and reusable after replay;
checkpoints must match the exact active route and format.

All database writers use `td18.dbWriteBatchEntries`, defaulting to 524,288
entries (512 Ki entries). This limits certificate `WriteBatch` entries,
inverse-state external SST keys per writer, and independent checker-state
`WriteBatch` keys. Smaller certificate queues or per-writer reconstruction
buffers further limit batch sizes. Final partial batches still drain at
completed persistence boundaries. A certificate entry can produce both a
witness put and an obstruction delete, so entries do not count individual
RocksDB operations. Both RTX 5090 profile launchers explicitly select 524,288 entries.

Certificate persistence also uses `td18.certificateQueueCapacity` and
`td18.certificateFlushSeconds`. Inverse sets use
`td18.reconstructionBufferKeys` and batched reads controlled by
`td18.certificateLookupBatchSize`. Queues and in-memory key buffers are
bounded. Class keys and disk iteration are deterministic at persistence
boundaries. `certificates/`, `data/`, work databases and ordinary logs are untracked.
Completed reference emission and verification logs and settings in
`logs/reference/` are committed.

Each handoff file has format `td-reconstruction-queue-v1`, a route/domain/budget
header, fixed-size records containing versioned canonical class keys and their
explicit budgets, and a final record count. An unfinished file keeps count -1.
Queue memory is bounded independently of its disk backlog; truncated records
and incomplete coverage are errors. Handoff files are temporary execution state,
never proof inputs or resume sources. After interruption, resume regenerates
the handoff from the latest complete sealed wave and replays stored certificates.

Each sealed-wave checkpoint has route ID `td18-eriksson-three-v1`, explicit
budgets and counts, and its own versioned file `eriksson-reconstruction-v1.txt`.
Resuming validates every record of the latest sealed set and its exact count.
Changing the GPU time allowance does not change the route or state format.
The checker requires fresh work outside the certificate database and never trusts
or resumes emitter sets. Checksums, obstruction markers and sidecar manifests
cannot replace regenerated proof obligations.

## Entry points and options

`emit` accepts `--phase=roots|endpoints|avoidance|all`,
`--partitions=FIRST-LAST`, `--endpoints=FIRST-LAST`, `--paper=PATH`,
`--database=PATH`, `--work=PATH`, and `--source-db=PATH`.
`--resume-work=PATH` requires the complete avoidance scope without ranges or a
witness-source import. `verify` supports the same phase/range selection and
fresh `--work`, but never emitter checkpoint inputs. `inventory` performs only
structural checks. Source counts and full sorting verification remain separate.
