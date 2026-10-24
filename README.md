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
- three-column-family RocksDB output using the raw TD(16) five-bit instance
  and fifteen-bit sorting encodings, a retained legacy intermediate-witness
  namespace, and versioned sidecar completion manifests;
- CPU-only intermediate prefix screening, CUDA-batched avoidance-preimage
  reconstruction with device-side canonical-key deduplication, and GPU root
  sorting for bounded-search obstructions;
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
prefix closures, and replays stored sorting witnesses. During development it
checks instances directly and permits selected row ranges and phases;
sidecar integrity validation is deferred.

No complete TD(18) certificate database or completed end-to-end verification
report is included. In particular, a sorting obstruction remains an
unresolved obligation and makes an emitter run unsuccessful.
Structurally complete coverage requires all 204 Table 2 rows, the residual
avoidance branches, and both exceptional oriented 19-cycle certificates.
Full Table 2 emission and completion of the avoidance runner ensure the two
exceptional certificates are present. The focused emitter can also populate
or repair that component independently. The checker rejects missing witness
obligations in the branches selected for a run.

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
ten-transposition bound, and the raw five-bit/three-symbol encoding used by
`../td16`. It fails unless both survivors produce complete,
replay-validated sorting certificates. The checker reproduces the DFS
coverage, excludes its unoriented output, and rejects either missing
certificate.

Previously found witnesses can be imported on CPU, without a new CUDA search:

~~~bash
mvn -pl emitter compile exec:exec \
  -Dexec.mainClass=br.unb.cic.td18.OrientedNineteenCycleEmitter \
  -Dtd18.args=--witnesses=/path/to/witnesses.txt
~~~

The text file contains two whitespace-separated hexadecimal fields per line:
the raw instance key and its encoded sorting witness. The emitter requires
both configured survivor classes and replays both witnesses before writing.
The file is a generated local artifact and must remain untracked.

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
`Q = 0` are recorded in sidecar manifests as already discharged and bypass
generation and search.
Rows with `Q = R` are directly generated and checked against `Q`, including
starred rows 130 and 134 in their normal positions. The manifest partitions
generated classes into trivial CPU/known-diameter closures, validated GPU
certificates, and residuals. CPU prefixes are not certificate records and are
not stored in RocksDB; an independent checker must regenerate and recheck those
closures. A row marker is complete only when it has no obstruction. Any native
obstruction is persisted as only its raw five-bit instance key, with an empty
value, in the `obstructions` column family before the sidecar row manifest.
Its row, annotated type, budget, and reduction path are reconstructed from
Table 2 rather than stored. After all selected rows have been processed, every such obstruction
is routed to the same final obstruction phase. Following the construction used
by `../td16`, the emitter restores every possible contracted fixed-point placement, applies
every inverse support for the selected reduction, and retains only reachable
predecessor types. It skips predecessor sizes already closed by a known
diameter, directly generates gap multiplicities, and labels restored fixed
points in current-cycle traversal order. This removes redundant labelings
while preserving the predecessor classes. The checker independently selects
the same label order from sequential insertions. It deduplicates each
one-reduction predecessor and runs only
the CPU prefix-closure test with budget 9. A settled predecessor closes its
represented root paths; every prefix miss is propagated through the final
inverse wave without an intermediate GPU attempt. The resulting 19-symbol root
classes are deduplicated by extended-toric class, screened again for a CPU
prefix closure, and otherwise sorted independently on the GPU with budget 10.
Table 2 and full-domain root witnesses retain raw keys in the default family.
The `avoidance-intermediate-witnesses` family remains only for compatibility
with existing three-family databases and is not consulted. Only a ten-move root obstruction leaves the run unsuccessful;
it is another key-only entry in the `obstructions` family. Versioned row and
range manifests live under the `proof-manifests` sidecar directory.
The artifact is ignored by Git and may be very large. Use a new or compatible
TD(18) database directory. Opening a compatible two-family database adds the
intermediate-witness family non-destructively; the emitter still rejects the
old prefixed single-family format.

To run or resume only the persisted obstruction phase from the current database
exist, use:

~~~bash
mvn -pl emitter -Pgpu compile exec:exec \
  -Dexec.mainClass=br.unb.cic.td18.ObstructionAvoidanceRunner \
  -Dtd18.certificateDb="$PWD/certificates" \
  -Dtd18.args="--work-database=$PWD/avoidance-work"
~~~

Both the work writer and the certificate writer disable WAL for all RocksDB
writes. The certificate writer flushes every `td18.certificateFlushSeconds`
seconds and at manifest boundaries.

The work database records candidate and prefix-miss inventories, checksums, and
completed reconstruction/screening waves. By default the inverse-support
applications, predecessor checks, twisted-bracelet canonicalization, and
per-batch key deduplication run in parallel across the configured CUDA devices.
The first batch of each wave is regenerated on the CPU; its application count
and full canonical key set must agree before the run continues. Set
`-Dtd18.avoidanceGpu=false` for the CPU backend,
`-Dtd18.avoidanceGpuBudgetMB=...` for the reconstruction memory cap,
`-Dtd18.avoidanceGpuBatchSize=...` for the number of inputs in one CUDA
sort/unique operation, or
`-Dtd18.avoidanceGpuVerifyBatches=...` for additional checked batches.

The work writer drains buffered keys at the configured cap and forces
memtables to disk every 60 seconds and at checked
manifest boundaries. Size-triggered drains use RocksDB's normal memtable
flushing instead of forcing a synchronous flush for every drain. Set
`-Dtd18.avoidanceBufferKeys=...` to change the cap. The
`td18.avoidanceRocksWriteBufferMB`, `td18.avoidanceRocksMaxWriteBuffers`,
`td18.avoidanceRocksWriteBatchKeys`, `td18.avoidanceRocksBackgroundJobs`, and
`td18.avoidanceRocksSubcompactions` properties tune bulk persistence.
The cap includes keys in an active drain and covers both column families;
producers wait for space when the writer falls behind. Successfully written
RocksDB batches release their Java references immediately. Drain logs
report the trigger, duration, current buffer size, and peak buffer size.
This buffering policy does not change stored keys or reconstruction counts.
Avoidance search progress and the CPU/GPU search summaries are reported every
`td18.gpuStatsPeriodSeconds` seconds (60 by default). Set it to `0` to disable
periodic reporting; each search wave still reports its final totals.
Set `-Dtd18.avoidanceTrustWaveManifest=true` to resume completed waves using
their recorded output inventories without rescanning the output sets. The
default is `false`; independent verification of the persisted sets is still
required for a checked computational artifact.

Work-database format version 2 and
avoidance-manifest version 4 count inverse
supports on canonical restorations after domain, depth, and known-diameter
tests, before parent-type filtering and class deduplication. Earlier work
databases and avoidance manifests are rejected. Use a new work directory to
reconstruct the avoidance phase from the existing Table 2 row artifacts; an
existing work database must use the current counting convention. Development
runs can replace an existing final avoidance sidecar.

## Check sorting certificates

The checker currently runs development checks: it checks instances and
sorting arguments without comparing sidecar fingerprints or requiring
completion manifests. Existing sidecars remain compatible but are not proof
inputs in this workflow. Final artifact-integrity validation in the research
plan remains pending.

Run the checker directly from the repository root:

~~~bash
MAVEN_OPTS="-Xmx3g -Dtd18.checkerWorkers=12" \
mvn -pl checker compile exec:java \
  -Dtd18.twistedBraceletWorkers=12 \
  -Dtd18.certificateDb="$PWD/certificates"
~~~

Use `-Dexec.args` to choose a starting point:

- `-Dexec.args="--rows=52-204"` checks all branches associated with those
  rows, restarting row 52 from the beginning.
- `-Dexec.args="--phase=rows --rows=2-16"` checks only those Table 2 rows.
- `-Dexec.args="--phase=avoidance --rows=130-134"` starts from persisted
  residual obstruction keys for those rows.
- `-Dexec.args="--phase=survivors"` checks only the two exceptional
  oriented 19-cycle certificates.

Periodic progress is printed once per minute. To save and follow the console
output, append `2>&1 | tee -a logs/checker-verification.log` to the command
and run this in another terminal:

~~~bash
tail -n 50 -F logs/checker-verification.log
~~~

Each avoidance run creates fresh scratch sets under
`checker/target/verification-work-*`. No launcher, source archives, backup
copies, or audit files are required.

The certificate database is the only emitter output consumed. The checker
opens it read-only, uses CPU search and witness replay, and independently
reconstructs residual predecessor branches in bounded batches with disk
class deduplication. It never reads the emitter's `avoidance-work` database.
Intermediate prefix misses are propagated; each full-domain root must close
by a CPU prefix or a replayed database witness. Missing witnesses and
unresolved obligations remain failures. Partial checks do not establish
coverage of omitted rows or branches, and an avoidance-only run does not
repeat the original Table 2 enumeration.

The checker reads Table 2 from the source document. It reconstructs the 53
root partitions, 360 annotated roots, and 204 terminal types without calling
the emitter's recursive type enumerator. It also checks all reached reduction
states and selected child partitions against the independent work-list
enumeration, including branches merged into the same terminal row. For
literal `Q = 0` rows, it
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

The checker rejects invalid cyclic-target instances, inapplicable moves,
missing witnesses or obstruction outcomes, and exceeded move budgets. It
also checks the mathematical inventory and representative counts. It does
not compare saved fingerprints or enforce sidecar completion records during
development. A structural-only run checks the inventory, the paper-backed
`Q` lemma, and the complete oriented-19 DFS without opening RocksDB:

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
