# Active route architecture

The [paper](td18-reduction-count.tex) specifies the mathematics and
[research plan](RESEARCH_PLAN.md) states proof completion criteria. The public
`WitnessEmitter` and `CertificateChecker` entries dispatch to their active
`ErikssonWitnessEmitter` and `ErikssonCertificateChecker` implementations.
The Maven modules remain independent: the checker has no emitter dependency.

`ErikssonRoute` defines the root domain 19, budget 10, endpoint domain
14, budget 7, and 74 endpoint annotations. `ErikssonInventory`
reads every active paper row in deterministic partition order. Explicit pending
rows support structure checks but cannot supply proof counts.

The emitter root stream uses `ErikssonRootGenerator` with normalized algebraic
cycles and prefix placement. `CyclicTargetMoves` contains mathematical move and
contraction operations. The checker has its own independent move implementation
and a budgeted `SearchState`; neither requires a superseded route container.
Source-side immediate and three-move constraints, forced target-cycle checks,
and twisted canonical comparisons prune prefixes. Known directed target paths
also certify immediate target-side closures. Full remaining leaves pass the
complete two-sided reduction test. Count mode retains no representative set.

The checker derives its own source predicates by arbitrary supports, uses
independent rank-placement state, and tests both full cycles directly. Its
selection does not call the emitter's endpoint-swapping or consecutive-triple
helpers. Both implementations are compared with independent small-domain
permutation oracles before large runs.

Endpoint classes use the existing fixed-content twisted-bracelet generator and
Burnside counter. Both modules independently regenerate the complete annotated
cover. Elementary diameter bounds close whole families; every other class
requires a persisted complete witness, replayed diameter prefix, or final inverse
coverage. Direct root and endpoint generation emits one representative per
valid-pair class within each type. Ordinary emission therefore skips the
preliminary certificate lookup and searches the generated class directly.
Source imports and reconstruction retain certificate reuse. Reconstruction
replays each source batch with `multiGet`; CPU and GPU sorting consume its
misses without repeating per-class reads. Root prefetch follows the same
bulk-lookup/miss-dispatch boundary. Every newly emitted result still follows
the checked persistence path.

`ErikssonInverseReconstruction` enumerates inverse gains zero through three on
both endpoints, restoring all deleted fixed symbols in all gaps. The emitter
uses image arrays and simultaneous gap choices. The checker uses permutation
multiplication and sequential insertion. Small-domain tests compare complete
predecessor class sets for every gain on both cycles.

`ErikssonAvoidance` performs three disk-backed waves: reverse gain two, reverse
both gains two/three, then reverse gains one/zero. Lookups and searches use each
state's explicit domain and budget; both after-one domains have budget 9.
Every final root uses 10. CPU sorting prefixes and intermediate GPU searches
are heuristics: a miss, depth limit or timeout preserves every inverse obligation.
CPU prefix workers, GPU dispatch workers and CPU inverse workers are independent.
A versioned disk queue feeds GPU searches; a second queues every GPU miss for
inverse generation. GPU workers never execute inverse callbacks or wait on their
persistence queues. Both handoffs have fixed-size validated records and exact
final counts. All pools join before sealing, with coverage equalities checking
source classification, every GPU result, and every transferred inverse case.
Progress reports count processed source cases against the full wave total and
show the remaining CPU, GPU and inverse work separately.
Intermediate CUDA searches explore the full obligation budget (8 or 9).
The former depth-five cap and its override have been removed.
`td18.reconstructionGpuMillis` defaults to 0 (no time cutoff). Budget-10 searches
always use full exploration depth 10 without a time cutoff.
Persistence is bounded and asynchronous. The checker
regenerates all waves from actual missing endpoint discharges, so missing
obstruction markers cannot suppress work.

CUDA's sorting tree excludes fixed symbols from supports, permits increases of
zero or two in the number of odd-length cycles, and additionally excludes
supports in three distinct algebraic cycles. The CPU prefix search permits
the latter when their odd-length-cycle increase is zero. A GPU search without
a time cutoff and with full exploration depth exhausts only this restricted
tree within its move budget.
Only replayed witnesses discharge a class; exhaustion, depth limits and timeouts all leave
it unresolved.

The kernel also skips a positional transposition `(i,j,k)` immediately followed
by `(i,i+k-j,k)`. These two segment interchanges return to the identical
cyclic-target pair. Deleting the adjacent inverse moves preserves any later
closure and saves two moves, so the skip preserves existence of a certificate
within the obligation budget.

Each CUDA warp keeps its parent state in shared memory and cooperatively
copies child batches into its locked queue. Every copying lane fences its
writes before the owner publishes queue metadata; all children are counted
before publication. Stop and completion polls use device-scoped relaxed
loads, with the libcu++ PTX mapping for Pascal and later architectures.
They do not publish queue entries or witness payloads.

Each search slot also holds a bounded cache of complete algebraic permutation
keys and the least depth visited. The target rho is fixed within that search,
so omega determines beta as a cycle, up to its starting position. A shallower
occurrence has at least as much remaining budget and exploration depth. The
only history restriction skips an immediate inverse edge to an already visited
parent; erasing that adjacent inverse pair preserves any successful continuation.
The cache compares all key bits under fenced locks. Hash collisions and full
probe sequences fall back to ordinary exploration. It is cleared between
searches, and a duplicate removal decrements the outstanding-work count.
Its 524,288 entries use 12 MiB per slot in addition to the configured queues.
The P100 queue allocation remains 16 times 896 MiB, or 14 GiB per device.

At an exploration cap, the consecutive-support scan first tests all moves
gaining at least two fixed symbols. If neither gain zero nor gain one can
close with the actual remaining obligation budget and known-diameter table,
general support enumeration is unnecessary: no child can be expanded and
no untested support can close. The result remains unresolved when the scan
fails. Cycle metadata is built only for nodes needing general enumeration.
This changes work per node without changing the represented search tree,
the exploration cap, or any closure budget.

The paper's capped all-odd lemma supplies an additional subtree rejection.
When the current 3-norm equals the remaining full budget, a successful
continuation has no zero moves available (zero means no change in the
odd-length-cycle count). If all cycles have odd length, every remaining
rank-decreasing move preserves that property. The kernel checks whether the
exploration cap can reach any rank at which the actual known-diameter table
permits an all-odd remainder to close. If it cannot, the subtree is unresolved.
Fixed points count as odd-length cycles; an even-length cycle disables this
rejection. Full-depth final-root searches are unaffected by this test.

Inverse generation batches up to 65,536 canonicalized keys per producer across
successive callbacks and
partitions them by a deterministic hash into independent writer shards.
Each shard has a lock-free queue and a single owner of its deduplication
buffer and sorted SST files. Producers never acquire a shared insertion
monitor or perform disk writes. Semaphores bound queued batches; producers
wait only when their destination queue is full. The default uses up to eight
writers, a 16-million-key aggregate deduplication window and 1,024 queued
batches, distributed across shards. All database writers honor
`td18.dbWriteBatchEntries` (524,288 entries by default); an inverse writer
flushes at the smaller of that limit and its share of the deduplication buffer.
Certificate and independent checker-state writers use the same property for
their RocksDB `WriteBatch` entry limits. Each inverse flush sorts distinct keys in unsigned
byte order, builds an SST outside the shared database write path, then ingests
it with global sequence numbers enabled. Duplicate keys across tables have
the same empty value and remain one set entry. SST construction bypasses WAL
and memtables; ingestion and compaction still require database coordination.
RocksDB allows up to sixteen background jobs and eight subcompactions.
Producers first publish to every shard with free space before waiting on a
full shard, so one slow destination cannot prevent all other writers receiving
the same batch's remaining partitions.

Producer buffers persist across callbacks on the same thread. Final partial
batches are queued after all generators join, before writer drain and sealing.
An atomic admission counter prevents sealing or closing while a generator
is active. After every generator joins, sealing stops admissions, drains and
joins every writer, flushes RocksDB and validates the exact sorted inventory.
Generation or writer failure prevents sealing and propagates to blocked
producers. Native resources outlive every writer. The deterministic key
format and the represented class set are unchanged.

During the final inverse wave, encoded root batches also feed a bounded
advisory GPU prefetch queue. GPU prefetch starts immediately with at most one
worker per GPU slot. Full-depth searches use a positive time slice (100 ms
by default), filling gaps between source batches without indefinitely holding
GPU slots needed by source checks. Early root searches overlap continued
inverse generation and SST persistence. Queue pressure never blocks
generation; recent-key suppression and discarded advisory work cannot omit
obligations. Started searches finish and use the normal checked certificate
path. The sealed root inventory is still exhaustively scanned afterwards.

Sealed checkpoints are versioned computation state. The latest-wave count,
route and budgets are validated on resume. Intrinsic certificates remain
route-independent and are replayed at the requested budget. Counts, successful
partial scopes, checkpoints and passing tests are distinct from a complete
end-to-end proof report.
