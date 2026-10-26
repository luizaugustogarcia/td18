# TD(18) two-reduction research plan

## Objective and status

The objective is to prove TD(18) <= 10 using the conditional route specified
in [td18-reduction-count.tex](td18-reduction-count.tex). Combined with the known
lower bound, that would establish TD(18) = 10.

No completed TD(18) proof artifact is currently included. Table 2 is a finite
inventory of 292 annotated types, not a diameter certificate. Its `R` column
counts all realizable extended-toric classes, while `Q` counts the classes
still requiring a sorting witness after checked TD(16) reuse. Every `Q` class
must be discharged within its displayed budget, subject to the local reduction
hypothesis.

Use [WORKFLOW.md](WORKFLOW.md) to map these stages to runnable commands and
output paths, [KNOWLEDGE.md](KNOWLEDGE.md) for framework references, and
[OPERATIONS.md](OPERATIONS.md) for run/resume configuration. This plan remains
the authority for completion criteria; reorganizing code or tooling does not
complete a stage.

## Stage 0 — certify the mathematical reduction

Produce proofs or precise citations for every nontrivial premise used by the
two-reduction method:

- fixed-point contraction preserves sorting distance;
- at every recursion depth, after testing known-diameter closure, a totally
  unoriented type is retained as a direct obligation without structural
  reductions. This is the selected routing policy, not a claim that
  transpositions are impossible;
- clearing an oriented 3-cycle has the stated effect;
- qualifying 2-moves within an odd-length cycle have the stated cycle-length
  effects; after oriented-3 clearing, the policy tests oriented odd-length
  cycles in increasing order of distinct length and includes their support-clearing
  alternatives when the cited classification and global budget apply;
  otherwise it continues to another cycle, an even pair, or retention;
  the separate length-15 classification below accounts for the length
  omitted from the cited TD(16) proposition;
- an independent checker reproduces the completed oriented-19-cycle DFS,
  rejects its unique unoriented survivor, verifies that the two oriented
  survivors are exactly the configured extended-toric classes, and replays
  both emitted ten-transposition certificates;
- the exceptional oriented 9-cycle candidate is interleaved with every
  admissible companion partition and orientation on 19 symbols; all six
  partitions and 18 annotations are covered, and the checker regenerates
  every class and replays its complete database witness within ten
  transpositions, rejecting missing obligations (the TD(16) extension artifacts
  cover only 17 symbols);
- the independent length-15 DFS visits 169,355 nodes and retains one
  unoriented cycle and the unique oriented candidate `gamma = beta_15^8`;
  its local target has three cycles, preventing support clearing. Its
  restricted interleavings yield 90 root classes in `(15O,2U,2U)` with
  budget 10 and 7 intermediate classes in `(15O,3U)` with budget 9, from
  360 and 27 candidate bracelets respectively. Regenerate both families,
  replay their 86 CPU prefix closures, and require complete witnesses for
  the remaining 11 root classes. The intermediate annotated type is not
  retained as a whole-type obligation;
- the even-pair reduction has the stated effect and is used only for types
  containing some oriented cycle; the two selected even-length cycles
  themselves may both be unoriented;
- every recursive transposition branch spends one move and lowers the 3-norm
  by one; support-clearing alternatives have their separate move costs;
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
- no structural outgoing branch from any totally unoriented type, at any
  depth; open such types are direct obligations;
- 292 distinct retained types with depth distribution 59, 45, and 188;
- preservation of rows 1–204 and the 88 early additions, with 51 roots and
  37 one-move states;
- the exact row order and metadata in Table 2.

The totally unoriented stop adds 67 obligations to the previous 225-type
inventory: 45 roots and 22 one-move states. No old type is removed; the
first 204 rows retain their positions and budgets.

The Java enumerator is one implementation. The independent checker must not
reuse its recursion code. Any documentation/code mismatch is a failed check,
not a choice of one source over the other.

Exit criterion: two agreeing enumerations of the complete Table 2 inventory.

## Stage 2 — certify realizability and class counts

For each Table 2 type, count the realizable extended-toric classes represented
by twisted bracelets. The checked implementation must enforce target
realizability; an unchecked cyclic-target candidate is not a class.

Validate the Burnside counter by direct orbit generation at tractable domains
and compare character sums with symmetry-constrained enumeration for every
group-element family. Empty types must be proved empty by the checked count,
not assumed from their annotation.

Exit criterion: a versioned count artifact containing every row key, count,
method identifier, total, and code revision, plus an independent
verification report. Independently verify the `Q` mapping: 52 direct
oriented-3 closures; 50 complete TD(16) successor-search closures; full
emission of the two starred rows whose checked searches found 495 and 1,541
obstructions; and totals `R = 692,894,074,401` and `Q = 688,413,622,139`.
The dispositions are 102 fully settled rows, 2 partially covered rows, and
188 rows not covered by these TD(16) results. The latter two groups retain
`Q = R`; 35 empty uncovered rows and the 102 settled rows give 137 literal
zero-`Q` rows. There are 46 empty types altogether, including 11 of the
settled rows.

## Stage 3 — exhaustively generate the classes

For every class counted by `Q`, directly generate exactly one representative.
This includes every class in starred rows 130 and 134 in ordinary table order.
Keep fixed-content encoding, auxiliary
standardization, twisted reflection, and realizability checks explicit.

Persisted partitions must be deterministic and complete. Use versioned formats
and deterministic keys. The verifier regenerates the expected class set,
checks actual generation counts against the required counts, and rejects
missing, repeated, malformed, or out-of-scope representatives. Checksums and
sidecar completion records are not proof requirements.

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
of producing the terminal pair. Predecessor sizes already closed by a known
diameter are skipped before restoration. The emitter directly generates gap
multiplicities and labels restored fixed points in current-cycle traversal
order. Permuting only the restored labels bijects the inverse supports and
preserves their predecessor classes, so one such label order suffices. The
checker independently enumerates sequential insertions and selects that label
order at the end. It retains only predecessors admitted by the
checked availability-aware type policy at the preceding depth. Later odd-cycle
and even-pair inverses conservatively cover possible failures of earlier
qualifying tests; actual predecessor moves are replayed directly. No totally
unoriented predecessor can supply a structural reduction edge. It deduplicates
the one-reduction predecessors by extended-toric class and runs only the CPU prefix
closure test within budget 9; it does not dispatch intermediate GPU searches.
A checked closure at that level also closes every represented root path after
prepending its selected first reduction. Every prefix miss is propagated
conservatively through the final inverse wave. The resulting full-domain roots
are again deduplicated by extended-toric class, checked for a CPU prefix
closure, and sent to the GPU independently with budget 10 only on a prefix
miss. Table 2 and full-domain root witnesses retain their raw keys in the
default family. The legacy `avoidance-intermediate-witnesses` family remains
only for database-schema compatibility and is not a proof input. A root obstruction is
persisted as another key-only entry in the `obstructions` column family and
remains unresolved. The versioned sidecar range manifest records
reconstruction counts, intermediate prefix-check and propagated-miss counts, root
search counts, all outcomes, the class-key checksum, and whether any root
obstruction remains.
Avoidance manifests use version 6 and the work database uses format version 4.
Reconstruction counts include inverse supports on canonical restorations after
the domain, depth, and known-diameter tests, before parent-type filtering and
class deduplication. Older work databases and avoidance manifests are rejected
because their predecessor sets may include structural branches excluded by
the current stop for totally unoriented types.
The emitter may execute inverse-support applications in CUDA batches. The
device reconstructs and forward-replays each parent, applies a transition table
derived from the Java reduction enumerator, computes the exact twisted-bracelet
canonical representative, and sorts and deduplicates its packed keys. The first
configured number of batches is regenerated by the CPU reference and rejected
unless both the pre-filter application count and complete key set agree. This
changes execution and buffering only; the independent checker and the manifest
counting convention remain unchanged.

Exit criterion: every class counted by `Q` has either a checked
prefix-plus-known-diameter closure or a replayable valid witness within its row
budget, and no unresolved residual remains.

## Stage 5 — independent end-to-end verification

The verifier must independently:

1. check the mathematical hypotheses recorded in Stage 0;
2. regenerate the 292 type rows;
3. verify every `R` count, every TD(16) discharge, every `Q` value, and every
   generated-class partition;
4. decode every representative as a valid cyclic-target pair;
5. recheck every trivial prefix closure, replay every sorting witness, and
   enforce the global and row budgets;
6. regenerate all exceptional 9-cycle root interleavings, both exceptional
   15-cycle companion families, and both exceptional 19-cycle classes,
   checking every prefix closure and witness against its budget;
7. reject any missing row, class, partition, witness, or unresolved obligation.

Only a complete verification report permits the upper-bound claim. The final
report must state all hypotheses and artifact versions explicitly.

## Implementation status

The repository currently implements the type enumerator, twisted-bracelet
generation/counting framework, cyclic-target validation, CUDA search, and an
emitter that streams Table 2 classes into a three-column-family RocksDB
database.
The default family stores raw TD(16)-encoded Table 2 and root witness pairs,
the legacy `avoidance-intermediate-witnesses` family is unused by the current
route, and `obstructions` stores raw instance keys with empty values.
Versioned sidecar manifests carry per-row and avoidance-range counts,
checksums, and completion records. It implements intermediate screening, the
full-domain avoidance-preimage treatment, focused CPU prefix screening and
certificate emission for the exceptional 15-cycle companion interleavings,
and complete sorting certificates for the exceptional oriented 9-cycle root
interleavings and the two
oriented 19-cycle survivors found by the completed depth-first search. A
CPU-only checker now independently reconstructs the 292-row inventory with a
separate work-list implementation, reproduces the 9,304,845-node
oriented-19-cycle classification and the 169,355-node oriented-15-cycle
classification, checks the paper-backed `Q = 0` discharge,
regenerates representatives and CPU prefix closures, and replays certificates.
The exceptional-nine branch regenerates all selected classes and checks their
complete witnesses directly from the certificate database. Its full 19-symbol
emission/checking run remains pending.

The exceptional-fifteen generator has checked counts of 360 candidate
bracelets and 90 valid root classes, and 27 candidate bracelets and 7 valid
intermediate classes. Its checker independently verifies the local
classification, regenerates both restricted families, and independently
replays CPU prefixes. All seven intermediate classes close with
`1 + 3 + TD(11) = 10`; 74 roots close with `3 + TD(12) = 10`, and five
more with `2 + TD(14) = 10`. These 86 closures are checked and regenerated
on every proof run. The remaining 11 roots require complete witnesses
within ten transpositions; their certificate checks remain pending.
Missing required witnesses and exceeded budgets fail verification.
For every branch, verification uses
exhaustive regeneration, witness replay, and checked closure arguments;
checksums and sidecar files are not proof inputs. Selected row ranges or
phases cover only their selected obligations. No completed large checker run
or verification report is included. Obstruction provenance is intentionally not
persisted: the avoidance phase and independent checker regenerate every
reachable predecessor type and reduction path from Table 2 and the checked
policy.
Stages 0 through 5 do not yet have the complete checked artifacts required
above.

Generated databases, checkpoints, logs, and certificates must remain
untracked.
