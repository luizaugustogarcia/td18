# TD(18) two-reduction computation

This repository studies the conditional two-reduction route in
[docs/td18-reduction-count.tex](docs/td18-reduction-count.tex). The route starts
from fixed-point-free cyclic-target types on 19 symbols, applies at most two
selected structural reductions, and leaves a complete inventory of 204
annotated types.

The repository does not currently prove TD(18) = 10. The local reduction
hypothesis and every generated class, bounded search, witness, count, and
verification artifact required by [the research plan](docs/RESEARCH_PLAN.md)
must be checked first.
The oriented 19-cycle DFS itself has been run through length 19: it visited
9,304,845 nodes, retained three normalized cycles, and left exactly two after
excluding the unique unoriented output. A focused emitter handles those two
classes; the CPU checker independently reproduces the classification and
requires both certificates.

## Method

A state consists of a contracted annotated cycle type T and the number s of
transpositions already used. Its remaining budget is 10 - s. A known diameter
closes a state when it fits that budget. The contracted cyclic domain has
`N = n + 1` symbols for ordinary order `n`. Otherwise the current policy selects,
in order:

1. clearing an oriented 3-cycle;
2. a qualifying move on the smallest oriented odd-length cycle of length at
   least 5;
3. merging the two largest even-length cycles;
4. generation and sorting when no selected reduction applies.

Each reduction edge spends one transposition and lowers the 3-norm by one.
The recursion stops after two edges. Table 2 lists all 204 resulting annotated
types. Its `R` column counts every realizable extended-toric class; its `Q`
column retains only the class obligations not already discharged by the
checked TD(16) successor results. Thus no inventory row is removed: 102 rows
are discharged by those results, and the other 102 retain `Q = R`. Fourteen
of the latter are empty types with `R = Q = 0`, so 116 rows literally display
`Q = 0`. Rows 130 and 134 are starred because the
checked TD(16) search found 495 and 1,541 eight-move obstructions there, but
both rows are emitted in full and in ordinary table order. The emitted total is
29,064,174,153 classes.

The cyclic-target and twisted-bracelet layer encodes a pair (omega, beta), with
target rho = omega * beta, as fixed-content words. Auxiliary standardization
and the twisted dihedral action identify extended-toric classes, and direct
generation emits one representative per realizable class. The twisted
reflection reverses word positions and inverts cyclic ranks on oriented beads;
it is not ordinary word reversal.

## Implemented components

The `emitter` module contains:

- deterministic enumeration of the 204 annotated types;
- direct twisted-bracelet generation and exact Burnside counting;
- cyclic-target realizability checks and extended-toric canonicalization;
- exhaustive Table 2 orchestration over the Java/JNI/CUDA exact bounded search;
- versioned RocksDB certificate output with row counts, checksums, and
  completion records;
- full-domain avoidance-preimage reconstruction and root sorting for
  bounded-search obstructions;
- focused emission of complete sorting certificates for the two oriented
  19-cycle survivors;
- certificate encoding and independent replay primitives;
- regression tests for the group action, counting, encoding, and reduction
  inventory.

The `checker` module is CPU-only and has no dependency on the `emitter`
artifact. It independently reconstructs the type
inventory with a separate work-list implementation, reproduces the complete
oriented-19-cycle depth-first classification, verifies the paper-backed
`Q = 0` partition, regenerates the required representatives, rechecks short
prefix closures, and replays stored sorting witnesses. It also compares every
row and avoidance-range manifest with recomputed counts and checksums.

No complete TD(18) certificate database or completed end-to-end verification
report is included. In particular, a sorting obstruction remains an
unresolved obligation and makes an emitter run unsuccessful.
Structurally complete coverage requires both emitter entry points: the main
Table 2 pipeline covers all 204 rows and their avoidance ranges, while the
focused oriented-19 emitter supplies the two exceptional survivor
certificates. The checker rejects a database missing either component.

## Validate the type inventory

~~~bash
mvn -pl emitter -am test

mvn -pl emitter compile exec:java \
  -Dexec.mainClass=br.unb.cic.td18.ReductionTypeEnumerator \
  -Dexec.args=--validate
~~~

The validation reports 53 admissible partitions, 360 annotated roots, and 204
distinct obligations with depth distribution 8, 8, and 188.

## Emit the oriented 19-cycle survivor certificates

On a configured CUDA machine, search the two oriented 19-cycle survivor
classes and store one validated complete sorting certificate for each:

~~~bash
mvn -pl emitter -Pgpu compile exec:exec \
  -Dexec.mainClass=br.unb.cic.td18.OrientedNineteenCycleEmitter \
  -Dtd18.certificateDb="$PWD/certificates" \
  -Dtd18.gpuDevices=0 \
  -Dtd18.gpuSlots=1 \
  -Dtd18.gpuBudgetMB=1024 \
  -Dtd18.gpuStatsPeriodSeconds=0
~~~

The emitter uses deterministic extended-toric class keys, a
ten-transposition bound, and the versioned certificate codec used by the
Table 2 pipeline. It fails unless both survivors produce complete,
replay-validated sorting certificates. The checker reproduces the DFS
coverage, excludes its unoriented output, and rejects either missing
certificate.

## Count realizable classes

Count one realizable twisted-bracelet representative per extended-toric class
for every Table 2 entry:

~~~bash
mvn -pl emitter compile exec:java \
  -Dexec.mainClass=br.unb.cic.td18.CountTD18Bracelets
~~~

The counter reads [docs/td18-reduction-count.tex](docs/td18-reduction-count.tex),
uses all available CPU cores to apply Burnside's identity, validates each
recorded `R` value, and appends each completed count to the untracked
td18-bracelet-counts.log file. It does not run a sorting search or verify
sorting witnesses.

To count an inclusive subset of rows, use, for example:

~~~bash
mvn -pl emitter compile exec:java \
  -Dexec.mainClass=br.unb.cic.td18.CountTD18Bracelets \
  -Dexec.args="--rows=50-204"
~~~

Optional --table and --log arguments select another input or output path. See
[the Burnside derivation](docs/td18-burnside-counting.md) for the counting
method.

## Emit sorting certificates

On a CUDA machine, enumerate one cyclic-target pair per realizable
extended-toric class of every Table 2 type and discharge it within the row's
displayed budget. A bounded CPU pre-pass first looks for at most three
transpositions creating fixed points at rate at least 1.6. If the known
diameter of the contracted remainder closes the global ten-move budget, the
class is marked trivially settled without a certificate. Only the remaining
classes are sent to CUDA; complete GPU witnesses are validated and written to
RocksDB.

~~~bash
mvn -pl emitter -Pgpu compile exec:exec \
  -Dtd18.certificateDb="$PWD/certificates" \
  -Dtd18.twistedBraceletWorkers="$(nproc)"
~~~

The default configuration uses CUDA device `0`, seven concurrent slots, a
1024 MiB search-queue budget per slot, 60-second CPU/GPU statistics, and a
five-second certificate flush period. The command above explicitly assigns one
twisted-bracelet worker per logical CPU; the default value `auto` also uses the
runtime's detected processor count. The same controls as the TD(16) emitter
are available under the `td18` prefix: `gpuDevices`, `gpuSlots`,
`gpuBudgetMB`, `gpuQueueCapacity`, `gpuStatsPeriodSeconds`,
`twistedBraceletWorkers`, `certificateDb`, and `certificateFlushSeconds`. Set
`gpuStatsPeriodSeconds` to zero to disable both periodic search-statistics
lines. For example, a row range can be emitted with:

~~~bash
mvn -pl emitter -Pgpu compile exec:exec \
  -Dtd18.certificateDb="$PWD/certificates" \
  -Dtd18.args="--rows=50-75"
~~~

The emitter reads Table 2 directly from
[docs/td18-reduction-count.tex](docs/td18-reduction-count.tex). Rows with
`Q = 0` are recorded as already discharged and bypass generation and search.
Rows with `Q = R` are directly generated and checked against `Q`, including
starred rows 130 and 134 in their normal positions. The manifest partitions
generated classes into trivial CPU/known-diameter closures, validated GPU
certificates, and residuals. CPU prefixes are not certificate records and are
not stored in RocksDB; an independent checker must regenerate and recheck those
closures. A row marker is complete only when it has no residual. Any native
obstruction is persisted as a versioned residual obligation before the row
manifest. After all selected rows have been processed, every such obstruction
is routed to the same final obstruction phase. Following the construction used
by `../td16`, the emitter restores every possible contracted fixed-point placement, applies
every inverse support for the selected reduction, and retains only reachable
predecessor types. It repeats reconstruction, without searching an
intermediate predecessor, until it reaches the full 19-symbol cyclic-target
domain corresponding to a permutation in S18. The resulting root classes are
deduplicated by extended-toric class and sorted independently with budget 10,
so every avoidance certificate has the root-domain key expected by the
checker. Only a ten-move root obstruction leaves the run unsuccessful.
Avoidance obstructions and the final range manifest are versioned database
records. The database is ignored by Git and may be very large. Use a new or
already versioned TD(18) database directory;
the emitter rejects an incompatible or unversioned nonempty database rather
than mixing formats.

## Check sorting certificates

Run the CPU-only checker against a complete emitted database:

~~~bash
mvn -pl checker -am install -DskipTests
mvn -pl checker exec:java \
  -Dtd18.certificateDb="$PWD/certificates" \
  -Dtd18.twistedBraceletWorkers="$(nproc)" \
  -Dtd18.twistedBraceletBatchSize=256
~~~

The checker reads Table 2 from the source document. It reconstructs the 53
root partitions, 360 annotated roots, and 204 terminal types without calling
the emitter's recursive type enumerator. For literal `Q = 0` rows, it
distinguishes two reasons for skipping generation. The 102 nonempty
discharges invoke the TD(16) results proved in
[*Twisted Bracelets for Sorting by Transpositions: the Transposition Diameter
of S_16*](https://arxiv.org/abs/2609.17493): Corollary
`cor:oriented-three-cycles` handles 52 rows, and the one-fixed-point-successor
computation in Section `sec:td16` handles 50 more. The other 14 zero-`Q` rows
are skipped only after the checked Burnside count confirms `R = 0`. The two
successor types with 495 and 1,541 residuals retain `Q = R` and are fully
generated. Representative lookups use position-preserving RocksDB multi-get
batches; `td18.twistedBraceletBatchSize` controls the batch size.

The checker rejects a missing or mismatched row manifest, certificate,
residual, avoidance manifest, survivor certificate, count, checksum, or move
budget. A structural-only run checks the inventory, the paper-backed `Q`
lemma, and the complete oriented-19 DFS without opening RocksDB or enumerating
the large class corpus:

~~~bash
mvn -pl checker exec:java \
  -Dexec.args=--structural-only
~~~

## CUDA build

CPU-side compilation and tests do not require CUDA. On a configured CUDA
machine, build the exact-search native library with:

~~~bash
mvn -Pgpu package
~~~

Building the native search does not itself discharge any Table 2 row. Only a
complete database followed by independent verification could do that.

## Lineage

The two-reduction document is the source of truth for this repository's proof
route. The sibling ../td repository remains the source for the shared
twisted-bracelet framework and its mathematical conventions; it is not an
alternate TD(18) computation.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
