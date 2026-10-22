# TD(18) two-reduction research plan

## Objective and status

The objective is to prove TD(18) <= 10 using the conditional route specified
in [td18-reduction-count.tex](td18-reduction-count.tex). Combined with the known
lower bound, that would establish TD(18) = 10.

No completed TD(18) proof artifact is currently included. Table 2 is a finite
inventory of 204 annotated types, not a diameter certificate. Its `R` column
counts all realizable extended-toric classes, while `Q` counts the classes
still requiring a sorting witness after checked TD(16) reuse. Every `Q` class
must be discharged within its displayed budget, subject to the local reduction
hypothesis.

## Stage 0 — certify the mathematical reduction

Produce proofs or precise citations for every nontrivial premise used by the
two-reduction method:

- fixed-point contraction preserves sorting distance;
- clearing an oriented 3-cycle has the stated effect;
- every oriented odd-length cycle either admits the qualifying transposition
  or belongs to an exhaustively classified exceptional class with a closure
  that fits the TD(18) global budget;
- an independent checker reproduces the completed oriented-19-cycle DFS,
  rejects its unique unoriented survivor, verifies that the two oriented
  survivors are exactly the configured extended-toric classes, and replays
  both emitted ten-transposition certificates;
- the even-pair reduction has the stated effect;
- every edge spends one transposition and lowers the 3-norm by one;
- each diameter value used to close a state is established independently of
  the target TD(18) claim.

Exit criterion: a checked derivation covering all hypotheses, conventions,
and exceptional configurations used by the enumeration.

## Stage 1 — certify the finite type enumeration

Independently reproduce and compare:

- 53 admissible fixed-point-free partitions of 19;
- 360 orientation-annotated roots;
- the selected child partitions and fresh annotations after each reduction;
- known-diameter closure at every reached state;
- 204 distinct terminal types with depth distribution 8, 8, and 188;
- the exact row order and metadata in Table 2.

The Java enumerator is one implementation. The independent checker must not
reuse its recursion code. Any documentation/code mismatch is a failed check,
not a choice of one source over the other.

Exit criterion: two agreeing enumerations and a deterministic checksum of the
complete Table 2 inventory.

## Stage 2 — certify realizability and class counts

For each Table 2 type, count the realizable extended-toric classes represented
by twisted bracelets. The checked implementation must enforce target
realizability; an unchecked cyclic-target candidate is not a class.

Validate the Burnside counter by direct orbit generation at tractable domains
and compare character sums with symmetry-constrained enumeration for every
group-element family. Empty types must be proved empty by the checked count,
not assumed from their annotation.

Exit criterion: a versioned count artifact containing every row key, count,
method identifier, total, code revision, and checksum, plus an independent
verification report. Independently verify the `Q` mapping: 52 direct
oriented-3 closures; 50 complete TD(16) successor-search closures; full
emission of the two starred rows whose checked searches found 495 and 1,541
obstructions; and total `Q = 29,064,174,153`.

## Stage 3 — exhaustively generate the classes

For every class counted by `Q`, directly generate exactly one representative.
This includes every class in starred rows 130 and 134 in ordinary table order.
Keep fixed-content encoding, auxiliary
standardization, twisted reflection, and realizability checks explicit.

Persisted partitions must be deterministic and complete. Each artifact needs
a versioned format, row identity, expected count, actual count, checksum, and
completion marker. A verifier must reject missing, repeated, malformed, or
out-of-scope representatives.

Exit criterion: the verified generated count for every row equals `Q`; rows
with `Q = 0` require the checked discharge artifact instead of generation.

## Stage 4 — discharge every sorting obligation

Let a row be reached after s reductions on a contracted domain of N symbols.
Discharge every representative remaining in `Q` within 10 - s moves. One valid
discharge is a checked prefix of d at most three transpositions that creates r
algebraic fixed points (adjacencies), satisfies r/d >= 1.6, and satisfies
s + d + TD(N-r-1) <= 10 after fixed-point contraction. Otherwise, find and
validate a complete sorting sequence of length at most 10 - s. Search
boundaries must be exhaustive and explicit.

The short prefix is not a full sorting witness and must not be stored as a
certificate. The independent verifier must regenerate the representative,
replay the prefix, check its fixed-point gain and rate, check the contracted
domain, and check the cited known diameter against the global budget.

If bounded search fails for a representative, store only its deterministic
raw five-bit instance key in the `obstructions` column family, with an empty
value. The checker regenerates the representative in its Table 2 context; the
row, terminal annotated type, attempted bound, root annotated types, ordered
reductions, and intermediate annotated types are reconstructed from Table 2
and the checked reduction recursion rather than duplicated in the database.

Such a record is a residual obligation, not evidence for the upper bound. The
TD(18) conclusion remains pending until every residual obligation is resolved
within the reduction argument.

The emitter implements the obstruction-avoidance construction adapted from
`../td16`. For an obstruction with budget `b`, it uncontracts the fixed points
in every cyclic gap and enumerates the inverse supports of each reduction family capable
of producing the terminal pair. It retains only predecessors admitted by the
checked TD(18) priority policy at the preceding depth. It deduplicates the
one-reduction predecessors by extended-toric class and searches each within
budget 9. A checked closure at that level also closes every represented root
path after prepending its selected first reduction. Only a conservative
intermediate search miss is propagated through the final inverse wave. The
resulting full-domain roots are again deduplicated by extended-toric class and
searched independently with budget 10. Complete intermediate witnesses use a
dedicated `avoidance-intermediate-witnesses` column family and a key prefixed
by format version, cyclic-domain size, and budget; Table 2 and full-domain root
witnesses retain their raw keys in the default family. A root obstruction is
persisted as another key-only entry in the `obstructions` column family and
remains unresolved. The versioned sidecar range manifest records
reconstruction counts, intermediate search and propagated-miss counts, root
search counts, all outcomes, the class-key checksum, and whether any root
obstruction remains.

Exit criterion: every class counted by `Q` has either a checked
prefix-plus-known-diameter closure or a replayable valid witness within its row
budget, and no unresolved residual remains.

## Stage 5 — independent end-to-end verification

The verifier must independently:

1. check the mathematical hypotheses recorded in Stage 0;
2. regenerate the 204 type rows;
3. verify every `R` count, every TD(16) discharge, every `Q` value, and every
   generated-class partition;
4. decode every representative as a valid cyclic-target pair;
5. recheck every trivial prefix closure, replay every sorting witness, and
   enforce the global and row budgets;
6. reject any missing row, class, partition, witness, or completion record.

Only a complete verification report permits the upper-bound claim. The final
report must state all hypotheses and artifact versions explicitly.

## Implementation status

The repository currently implements the type enumerator, twisted-bracelet
generation/counting framework, cyclic-target validation, CUDA search, and an
emitter that streams Table 2 classes into a three-column-family RocksDB
database.
The default family stores raw TD(16)-encoded Table 2 and root witness pairs,
`avoidance-intermediate-witnesses` stores domain-separated intermediate
witnesses, and `obstructions` stores raw instance keys with empty values.
Versioned sidecar manifests carry per-row and avoidance-range counts,
checksums, and completion records. It implements intermediate screening, the
full-domain avoidance-preimage treatment, and focused emission of complete
sorting certificates for the two
oriented 19-cycle survivors found by the completed depth-first search. A
CPU-only checker now independently reconstructs the 204-row inventory with a
separate work-list implementation, reproduces the 9,304,845-node
oriented-19-cycle classification, checks the paper-backed `Q = 0` discharge,
regenerates representatives and CPU prefix closures, replays certificates,
and checks row and avoidance manifests. No completed large checker run or
verification report is included. Obstruction provenance is intentionally not
persisted: the avoidance phase and independent checker regenerate every
reachable predecessor type and reduction path from Table 2 and the checked
policy.
Stages 0 through 5 do not yet have the complete checked artifacts required
above.

Generated databases, checkpoints, logs, and certificates must remain
untracked.
