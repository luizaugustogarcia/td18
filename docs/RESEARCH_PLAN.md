# TD(18) research and computation plan

## Objective

Prove `TD(18) <= 10`; together with the known lower bound, this establishes
`TD(18) = 10`.

Let `del_x(pi)` be the permutation obtained by deleting symbol `x` from `pi`
and applying the unique order-preserving relabeling to `[n-1]`. For every
symbol `x`,

```text
d(pi) <= d(del_x(pi)) + 1.
```

Sort `del_x(pi)` while carrying `x` with a neighboring block, then place `x`
with one final transposition. More generally, if `d(pi) >= r + 1`, every
one-symbol deletion of `pi` has distance at least `r`.

Write `E_n` for the extended-toric classes at distance `TD(n)`, and write

```text
H_n(r) = { C in S_n / extended-toric equivalence : d(C) >= r }.
```

At class level, represent an instance by the cyclic-target pair
`(omega, beta)`, with `rho = omega * beta`. Delete a symbol from both `beta`
and `rho`, then relabel. The multiset of the `N` circular removals is invariant
under toric rotation, and reflection commutes with deletion. Conversely,
inserting a new symbol into one of the `N` gaps of each cycle gives `N^2` raw
children of one representative. Canonicalizing at size `N + 1` and
deduplicating covers all child extended-toric classes.

## Non-negotiable proof distinction

A successful bounded search supplies a sorting witness and proves an upper
bound. Membership in an extremal or threshold set requires the other
direction: exhaustive failure at the preceding depth. The TD(16) certificate
contains upper-bound witnesses; it does not by itself compute `E_16`.
At orders 12 through 17 the established diameter supplies the upper bound,
so an additional witness search is unnecessary. Only the order-18 stage must
produce new upper-bound witnesses. `H_13(7)` retains distances seven and eight
by testing failure through depth six, without separating those two distances.

## Stage 0 — trusted primitives (implemented)

Deliverables:

- circular deletion from `beta` and `rho`, with order-preserving relabeling;
- all `N^2` inverse insertions;
- canonical keys that support algebraic fixed points;
- deduplication of child classes only after insertion at the larger order;
- persistent membership tables with format, order, count, and checksum;
- independent tests of the deletion multiset and child-set symmetry claims.

Required tests:

- deleting the newly inserted symbol recovers the parent;
- exactly `N` of the `N^2` insertions make the new symbol a fixed point of
  `omega`;
- representatives in one extended-toric class have the same multiset of
  removal classes;
- the `N^2` insertions of equivalent representatives yield the same set of
  canonical child classes;
- exhaustive comparison with direct permutation enumeration at small orders.

The implementation supplies these primitives in the `pipeline` module. A
stage is not complete until its manifest has been written. Verification
recomputes the stored count and checksum and independently repeats the
generation, removal, and search obligations.

## Stage 1 — compute `E_12`

The known values `TD(11) = 6` and `TD(12) = 7` imply that an order-12
diametral class has no algebraic fixed point. Enumerate every fixed-point-free
extended-toric class on the 13-symbol cyclic domain with the direct
twisted-bracelet generator. Retain exactly the classes that fail exhaustive
search through depth six. The known bound `TD(12)=7` supplies the matching
upper bound without a second search.

Exit criterion: complete generator checkpoints, a deterministic `E_12`
manifest and repeated depth-six failures, using the established upper bound.

## Stage 2a — propagate `E_12` to `E_13`

If a class belongs to `E_13`, then its distance is eight and every deletion
has distance at least seven. Since `TD(12) = 7`, every deletion belongs to
`E_12`. Stream the `N^2` children of `E_12`, reject each child having a
removal outside `E_12`, canonicalize and deduplicate the survivors, and
retain exactly the classes that fail exhaustive search through depth seven.
The established bound `TD(13)=8` supplies the upper bound for retained classes.

The [rearrangement database](https://mirza.ic.unicamp.br/bioinfo/index.jsf)
reports 427 **ordinary permutations** in `S_13` at distance eight. This is a
useful regression count, but it is not the cardinality of `E_13`, whose
elements are extended-toric classes. Validation must compare like with like by
expanding stored classes to ordinary permutations or by canonicalizing the
database records.

Exit criterion: a complete `E_13` manifest, independent regeneration of all
removal-qualified children, repeated depth-seven failures, and a comparison
with the 427 ordinary permutations, using the established upper bound.

## Stage 2b — compute `H_13(7)`

The fixed-point part of `H_13(7)` is exactly `Expand(E_12)`: contracting a
fixed point preserves distance, and `E_12` itself has no fixed point. For the
fixed-point-free part, globally enumerate the 14-symbol cyclic domain with
the twisted-bracelet generator. A class belongs to `H_13(7)` exactly when it
fails exhaustive search through depth six. The known diameter `TD(13)=8`
supplies the upper bound. There is no need to
classify this global enumeration again through depth seven because `E_13` is
computed independently by Stage 2a.

Exit criterion: complete global generation of the fixed-point-free part,
complete `H_13(7)` manifest, contraction checks for the fixed-point part,
repeated depth-six failures and confirmation that every
member of `E_13` belongs to `H_13(7)`.

## Stage 3 — propagate `H_13(7)` to `E_14`

An order-14 class of distance eight has every removal at distance at least
seven. Therefore:

1. stream all raw children of `H_13(7)`;
2. reject a child as soon as one removal lies outside `H_13(7)`;
3. canonicalize each survivor and deduplicate it globally;
4. retain the depth-seven failures, using the known bound `TD(14)=8`.

These survivors are exactly `E_14`, because `TD(14) = 8`. The fixed-point
members provide an additional check: each has exactly one fixed point and
contracts to `E_13`.

The implementation fuses insertion, removal filtering, canonicalization,
deduplication, and search. It does not persist the full unfiltered child set.

## Stage 4 — propagate `E_14` to `E_15`

Since `TD(15) = 9 = TD(14) + 1`, every member of `E_15` has all removals in
`E_14`. Apply the same fused propagation, fail classes through depth eight,
and use the established upper bound `TD(15)=9`. Because `TD(15) > TD(14)`, every retained
class must be fixed-point-free.

The historical hard instances remain useful regressions:

- all 2,036 residual obstructions from the TD(16) computation;
- the published Eriksson diametral permutation;
- the two published BSB 2012 examples;
- `rho_4 disjoint-union u_(10,7)`;
- `rho_4 disjoint-union u_(10,8)`.

Their exact encodings and bibliographic sources must be recorded before a
proof artifact is accepted.

## Stage 5 — compute the complete `E_16`

Split by algebraic fixed points.

### Fixed-point part

By contraction and `TD(15) > TD(14)`, this part is exactly `Expand(E_15)`.
Generate it by insertion, retain children with a fixed point, canonicalize,
and deduplicate. Verify that contraction returns a member of `E_15`.

### Fixed-point-free part

Enumerate every fixed-point-free extended-toric class on the 17-symbol cyclic
domain using the direct generator from the TD(16) work. The expected scale
from the project discussion is roughly `2.1 * 10^11` classes. This is the
remaining global-enumeration bottleneck; propagating only `E_15` would be
incomplete because `TD(15) = TD(16) = 9`.

Use the approximation upper bound only where its hypotheses apply. For
3-norm `k < 8`, use the Bafna–Pevzner `floor(3k/2)` guarantee. For `k >= 8`,
use the stronger `floor(11k/8)` guarantee. In the depth-eight classification,
3-norm at most five is discharged by the upper bound; norms six, seven, and
eight require exact work with two, one, and zero units of slack,
respectively.

For every retained fixed-point-free class, exhaustive depth-eight failure
proves distance greater than eight, while the established bound `TD(16)=9`
supplies the matching upper bound. Persist the union of both parts as `E_16`.

## Stage 6 — propagate `E_16` to `E_17`

Stream children of `E_16`, keep only those whose every circular removal lies
in `E_16`, canonicalize and deduplicate the survivors, and search them
exhaustively through depth nine. The failures form `E_17`, because
`TD(17) = 10`; no additional witness search is needed.

Exit criterion: verification independently regenerates every qualified child,
repeats each depth-nine failure, and applies the established diameter bound.

## Stage 7 — certify `TD(18) <= 10`

Stream order-18 children of `E_17`, retain only those whose every removal lies
in `E_17`, and find a sorting sequence of length at most ten for every
survivor. No exact characterization of `E_18` is required: complete candidate
generation plus a verified length-ten witness for every candidate rules out
distance eleven.

## Performance scope

The current design keeps twisted-bracelet generation at ordinary orders 12,
13, and 16. Child generation at orders 13, 14, 15, 17, and 18 is CPU-side and
fused with early removal rejection so raw child sets are never materialized.
The order-13 child propagation computes `E_13`; the independent global
order-13 generation computes the larger threshold set `H_13(7)` needed for
`E_14`.
Fixed-point expansion is a special case: matching predecessor gaps in the two
cycles directly generates its `N` children, and parents are processed in
parallel across all available CPUs. It does not enumerate and filter `N^2`
general insertions.
GPU acceleration of the `N^2` insertion/canonicalization step is parked; CUDA
is used only for exact bounded sorting. Profiling the persisted candidate
counts should precede any GPU implementation of propagation.

All propagated stages share bounded parent batches and round-based deletion
filtering. Deduplication retains one raw insertion per canonical child class,
so the new largest symbol still identifies the inverse deletion. After
checking the generating parent's membership once, the filter reuses that
known removal without reconstructing or querying it for each child. Other
deletions may be evaluated on the raw representative because their class
multiset and its minimum are representative-invariant; qualified children are
emitted canonically. Each round constructs one deletion per surviving child
and queries membership with RocksDB `MultiGet`; a miss discards that child
before later deletions are constructed. This changes neither the deletion criterion nor
the set of generated children. Propagation recomputes interrupted parent
batches and overwrites identical output keys.

Each qualified canonical child is classified only by the parent with the
least canonical removal key. Insertion class sets are representative-invariant,
so the owner always regenerates the child. A removal key smaller than the
current parent's key rejects that child from this parent's work before its
membership lookup and all later deletion rounds. If the smaller removal is
absent, the child fails the deletion criterion; if present, this parent cannot
be the owner. Per-parent deduplication and this ownership rule require no global
candidate membership table. The independent verifier regenerates insertions
and repeats removal checks separately.
Overlapping bounded-search requests share one backend result per class and
depth. Known-diameter stages perform only exclusion searches; order 18 retains
the witness search needed for its new upper bound.

All RocksDB stores share the same configurable cache policy: cached point
reads, a bounded native block cache per store, prioritized index/filter
blocks, and non-polluting sequential scans. Bloom filters affect newly built
tables, not old tables merely reopened with the new options. Cache and batch
budgets are order-independent performance settings, not proof assumptions.

## Data products

Each stage should produce:

- a manifest naming the format version, mathematical order, equivalence
  convention, search bound, code revision, count, and checksum;
- deterministic class keys or deterministic key partitions;
- resumable checkpoints separate from proof artifacts;
- the established upper bound for stages through order 17, replayable witnesses
  for order 18, and reproducible exhaustive searches for failures;
- a verification report that independently repeats generation and filtering.

The current manifest format records the format version, stage name, cyclic
domain size, count, and SHA-256 checksum. New known-diameter stages also record
their upper bound and its source. Code revision, equivalence metadata,
and search-bound metadata remain required before archival proof runs.

Large products belong under ignored `data/`, `certificates/`, or
`checkpoints/` directories, never in Git.
