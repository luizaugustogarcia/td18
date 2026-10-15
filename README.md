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
have `Q = 0`, two are partially settled, and 100 have `Q = R`. The remaining
total is 26,458,991,245 classes.

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
- certificate encoding and independent replay primitives;
- regression tests for the group action, counting, encoding, and reduction
  inventory.

No complete TD(18) certificate database or independent end-to-end verification
artifact is included. In particular, a sorting miss remains an unresolved
obligation and makes an emitter run unsuccessful.

## Validate the type inventory

~~~bash
mvn -pl emitter -am test

mvn -pl emitter compile exec:java \
  -Dexec.mainClass=br.unb.cic.td18.ReductionTypeEnumerator \
  -Dexec.args=--validate
~~~

The validation reports 53 admissible partitions, 360 annotated roots, and 204
distinct obligations with depth distribution 8, 8, and 188.

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
  -Dtd18.certificateDb="$PWD/certificates"
~~~

The default configuration uses CUDA device `0`, seven concurrent slots, a
1024 MiB search-queue budget per slot, 60-second CPU/GPU statistics, and a
five-second certificate flush period. The same controls as the TD(16) emitter
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
[docs/td18-reduction-count.tex](docs/td18-reduction-count.tex) and checks that
direct generation equals the recorded `R` count for each selected row. It
searches all `R` classes, while recording `Q` in the row manifest so the
independent checker can enforce the proof route's reuse boundary. The manifest
partitions generated classes into trivial CPU/known-diameter closures,
validated GPU certificates, and residuals. CPU prefixes are not certificate
records and are not stored in RocksDB; an independent checker must regenerate
and recheck those closures. A row marker is complete only when it has no
residual. Any native miss is reported as a residual obligation, and the
process exits unsuccessfully after safely flushing successful certificates.
The database is ignored by Git and may be very large. Use a new or already
versioned TD(18) database directory; the emitter rejects a nonempty unversioned
database rather than mixing formats.

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
