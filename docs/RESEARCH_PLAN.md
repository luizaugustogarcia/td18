# TD(18) research and computation plan

## Objective

Prove `TD(18) <= 10`; together with the known lower bound, this establishes
`TD(18) = 10`.

Let `del_x(pi)` be the permutation obtained by deleting symbol `x` from `pi`
and then applying the unique order-preserving relabeling to `[n-1]`. The
reduction driving the computation is the following elementary lemma. For every
symbol `x`,

```text
d(pi) <= d(del_x(pi)) + 1.
```

Sort `del_x(pi)` while carrying `x` with its neighboring block, then place `x`
with one final transposition. Hence, if `d(pi) = TD(n - 1) + 1`, every removal
of `pi` lies in `E_(n-1)`.

At class level, use the cyclic-target representation `(omega, beta)` with
`rho = omega * beta`. Delete a symbol from both `beta` and `rho` and relabel.
The multiset of the `N` circular removals is invariant under toric rotation;
reflection commutes with deletion. Conversely, inserting a new symbol into
one of the `N` gaps of each cycle gives `N^2` raw children of one
representative. Canonicalizing at size `N + 1` and deduplicating covers all
child extended-toric classes.

## Non-negotiable proof distinction

A successful bounded search supplies a sorting witness and proves an upper
bound. An extremal set requires the other direction: exhaustive failure at
the preceding depth. The TD(16) certificate contains upper-bound witnesses;
it does not by itself compute `E_16`.

## Stage 0 — trusted primitives (implemented)

Deliverables:

- circular deletion from `beta` and `rho`, with order-preserving relabeling;
- all `N^2` inverse insertions;
- canonical keys that support algebraic fixed points;
- deduplication of child classes only after insertion at the larger order;
- persistent membership tables with version, order, count, and checksum;
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
stage is not considered complete until its manifest has been written and a
verification run has recomputed its count and checksum.

## Stage 1 — compute and validate E_15

`TD(15) = 9` and `TD(14) = 8`, so a diametral order-15 instance has no
algebraic fixed point. Compute the complete fixed-point-free `E_15` and prove
both sides for every retained class:

- no sorting sequence of length at most eight exists;
- a sorting sequence of length nine exists.

Validation corpus from the project discussion:

- all `2,036` residual obstructions from the TD(16) computation;
- the published Eriksson diametral permutation;
- the two published BSB 2012 examples;
- `rho_4 disjoint-union u_(10,7)`;
- `rho_4 disjoint-union u_(10,8)`.

Before the stage is accepted, record exact encodings and bibliographic sources
for the five named published/construction examples above. The statement that
`E_13` has 427 classes is a useful small-order regression target and must also
be tied to the exact equivalence convention used by the implementation.

Exit criterion: two reproducible passes agree on the key set, cardinality,
checksum, depth-eight failures, and depth-nine witnesses.

## Stage 2 — compute the complete E_16

Split by algebraic fixed points.

### Fixed-point part

By contraction and `TD(15) > TD(14)`, this part is exactly `Expand(E_15)`.
Generate it by insertion, retain children with a fixed point, canonicalize,
and deduplicate. Verify that contraction returns a member of `E_15`.

### Fixed-point-free part

Enumerate every fixed-point-free extended-toric class on the 17-symbol cyclic
domain using the direct generator from the TD(16) work. The expected scale
from the project discussion is roughly `2.1 * 10^11` classes.

Use the Bafna–Pevzner lower bound to route exact depth-eight work:

- lower bound at most 6 is excluded from `E_16` by the 1.375 guarantee;
- lower bound 8 has zero slack and reduces to the existence of an all-2-move
  sorting sequence;
- lower bound 7 has one unit of slack.

These reductions are proof rules only after their integer rounding and all
algorithmic preconditions have been formalized and tested.

For every retained class, exhaustive depth-eight failure proves distance
greater than eight, while the established `TD(16) = 9` result supplies the
matching upper bound. Persist the union of the fixed-point and
fixed-point-free parts as the complete `E_16`.

Exit criterion: repeated enumeration and verification agree on per-route
counts, the final count/checksum, and every key's fixed-point partition.

## Stage 3 — compute E_17

For every class in `E_16`:

1. generate its raw insertions;
2. canonicalize at the 18-symbol cyclic domain and deduplicate globally;
3. compute every circular removal of each child;
4. keep the child only if every removal key belongs to `E_16`;
5. search each survivor exhaustively through depth nine.

The failures form `E_17`, because `TD(17) = 10`. Store a depth-ten witness for
each retained class as a validation artifact.

Exit criterion: the filter is reproduced from the immutable `E_16` table,
verification repeats every depth-nine exhaustion result and replays every
depth-ten witness.

## Stage 4 — certify TD(18) <= 10

Generate order-18 candidates from `E_17`, canonicalize, deduplicate, and apply
the all-removals-in-`E_17` filter. Find and persist a sorting sequence of
length at most ten for every survivor. Verification checks the candidate key
set, rejects missing records, and replays every move.

No exact characterization of `E_18` is required for the upper bound: complete
candidate generation plus a verified length-ten witness for every candidate
is sufficient.

## Data products

Each stage should produce:

- a manifest naming the code revision, format version, mathematical order,
  equivalence convention, and search bound;
- deterministic sorted keys or deterministic key partitions;
- counts and cryptographic checksums per partition;
- resumable search checkpoints separate from proof artifacts;
- witnesses for successful searches and explicit exhaustive-search metadata
  for failures;
- a verification report, including repeated bounded searches for extremal
  classes.

Large products belong under ignored `data/`, `certificates/`, or
`checkpoints/` directories, never in Git.
