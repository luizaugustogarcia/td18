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
- the qualifying move exists for every oriented odd-length cycle of each
  required length, including all exceptional local configurations;
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
oriented-3 closures, 52 rows matching the checked TD(16) successor searches,
the residual counts 495 and 1,541, and total `Q = 26,458,991,245`.

## Stage 3 — exhaustively generate the classes

For every class counted by `Q`, directly generate exactly one representative,
or import and independently verify the residual representative set for the two
partially settled TD(16) types. Keep fixed-content encoding, auxiliary
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

If bounded search fails for a representative, record:

- the representative and deterministic class key;
- the root annotated type on 19 symbols;
- the ordered reduction path and every intermediate annotated type;
- the attempted bound, search configuration, and code revision.

Such a record is a residual obligation, not evidence for the upper bound. The
TD(18) conclusion remains pending until every residual obligation is resolved
within the reduction argument.

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
emitter that streams Table 2 classes into a versioned RocksDB certificate
database with per-row counts, checksums, and completion records. Stages 0
through 5 do not yet have the complete checked artifacts required above.

Generated databases, checkpoints, logs, and certificates must remain
untracked.
