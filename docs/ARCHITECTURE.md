# Architecture

The mathematical contract is [the source paper](td18-reduction-count.tex).
The workflow has three boundaries: exhaustive class generation, sorting
search, and independent certificate verification.

## Mathematical core

A cyclic-target candidate is unchecked until its current cycle, even
algebraic permutation, and full target have been validated. The general
model preserves fixed points. Fixed-content generators require a contracted
fixed-point-free pair. Candidate-orbit keys and valid-pair class keys are
distinct immutable values.

The joint package contains the diameter table, paper-backed root inventory,
independently enumerable endpoint inventory, joint transposition analysis,
and explicit support-clearing patterns. The analysis tests every qualifying
first move and all second moves creating at least two fixed points. It does
not select a cycle type before testing move availability.

The fixed-content bracelet generator handles totally unoriented roots and
annotated endpoints. The constrained root generator fixes algebraic cycle
labels, assigns current-cycle positions, and prunes favorable-prefix
constraints during recursion. Both enforce twisted symmetry and full-target
realizability before emitting a class.

## Emitter

The emitter directly generates totally unoriented roots and oriented joint
residuals, validates the paper counts, and applies the cited support-clearing
closures. Every other direct root is searched with budget ten. Endpoints use
eight and reconstructed intermediates use nine.

The CUDA sorter allows transpositions with odd-length-cycle gains zero or
two. It searches up to the complete budget and stops at identity or when
contraction leaves a domain whose established diameter fits the remaining
budget. Fixed points are counted as data at the root and updated after each
transposition. The CPU generates classes and replays results; it performs no
prefix sorting search.

Complete witnesses and diameter closures are validated and persisted with
separate kind tags. CUDA initialization is lazy. The certificate writer uses
a bounded queue of 16,384 entries by default, configurable through
td18.certificateQueueCapacity. The joint-generation and root-witness task
queues each permit 256 pending tasks. CPU worker counts are automatic.
Each disk set buffers at most 16,384 unique keys before flushing to RocksDB.

Avoidance restores two fixed points to each unresolved endpoint and reverses
every compatible second move. Disk-backed class deduplication precedes GPU
search with budget nine. Successful intermediate discharges are persisted.
The second inverse wave restores one fixed point and reconstructs roots;
remaining roots receive budget-ten searches. The root result starts at the
root and is independent of the selected reduction prefix.

## Checker

The checker has no dependency on the emitter artifact. It owns independent
inventory generation, root generation, inverse construction, and replay.
Its interface to the emitter is the mathematical certificate encoding.
The source inventory is checked against independently regenerated partitions
and endpoint annotations. Its structural mode validates the finite route
and tests without pretending to check a certificate database.

Proof verification regenerates every selected root and endpoint. Each endpoint
without an eight-move discharge triggers inverse coverage even when no
obstruction marker is present. Regeneration also makes sidecar manifests
unnecessary as proof inputs. A longer valid witness does not close a smaller
budget; an incorrect witness is rejected.

## Persistence

The certificate database has two RocksDB column families. The default family
contains the UTF-8 schema entry td18-schema = joint-selection-v2 and raw
canonical instance keys mapped to complete witnesses or diameter closures. The obstructions family
maps raw keys to empty values. The standard current cycle is implicit in the
five-bit key, so pairs are canonicalized before writing. Each transposition
uses three five-bit symbols. Each value starts with a kind byte and an exact
move-count byte. Zero-move closures have a two-byte record, so empty values
are never treated as sorting proofs. The checker replays every stored move,
requires identity for complete records, and derives the remaining diameter
from the actual nonfixed symbols for closure records.

A separate optional TD(16) source is opened read-only. Only replayed witnesses
within the requested budget are reused. Operational reconstruction work sets hold deterministic extended-toric
class keys on disk and require fresh directories for each invocation. Reruns
regenerate work and reuse validated certificates. Scratch data does not
replace regeneration in the proof checker. Generated databases, work sets, checkpoints,
logs, and certificates remain untracked.
