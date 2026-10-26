# Computational operations

Use [the workflow](WORKFLOW.md) for stage order and completion requirements,
and tools/td18 help for the command launcher. This guide preserves the direct
Maven commands and detailed configuration. Existing command class names and
artifact paths remain available.

These are development operations. The current checker defers sidecar integrity
and completion enforcement; the complete proof requirements remain in
[the research plan](RESEARCH_PLAN.md).

## Validate the type inventory

~~~bash
mvn -pl emitter -am test

mvn -pl emitter compile exec:java@cpu-cli \
  -Dexec.mainClass=br.unb.cic.td18.ReductionTypeEnumerator \
  -Dexec.args=--validate
~~~

The validation reports 53 admissible partitions, 360 annotated roots, and 204
distinct obligations with depth distribution 8, 8, and 188.

Open [the Table 2 provenance graph](table2-provenance.html) in a browser
to trace any row through every selected reduction and intermediate annotated
type back to its roots. Search by row or annotation, filter by root, inspect
the complete ordered path list, or save the displayed graph as a standalone
SVG. The graph includes empty and TD(16)-discharged rows in table order.
Arrows describe type-level branches with fresh annotations under the local
reduction hypothesis; class realizability and sorting are separate checks.

Regenerate the standalone HTML from Table 2 and the Java reduction policy:

~~~bash
mvn -pl emitter compile exec:exec \
  -Dexec.mainClass=br.unb.cic.td18.Table2ProvenanceGraph
~~~

Generation rejects incomplete or mismatched Table 2 inventories. The embedded
format has deterministic state and edge keys, counts, a completion flag, and
SHA-256 fingerprints of the source document and graph payload. The browser
checks its structure and payload checksum when Web Crypto is available. The
graph is a documentation artifact; it does not supply any pending proof
certificate or recount the recorded R and Q values.

## Emit the oriented 19-cycle survivor certificates

Both classes already have explicit ten-transposition clearing sequences; see
[the mathematical knowledge guide](KNOWLEDGE.md#explicit-clearing-of-the-two-oriented-19-cycle-exceptions).
The checker tests independently replay those sequences, and the emitter tests
exercise CPU import with known witnesses. A CUDA search is optional when
valid witnesses are available; the finished proof database still needs both.

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
mvn -pl emitter compile exec:java@cpu-cli \
  -Dexec.mainClass=br.unb.cic.td18.CountTD18Bracelets
~~~

The counter reads [docs/td18-reduction-count.tex](td18-reduction-count.tex),
uses all available CPU cores to apply Burnside's identity, validates each
recorded `R` value, and appends each completed count to the untracked
td18-bracelet-counts.log file. It does not run a sorting search or verify
sorting witnesses.

To count an inclusive subset of rows, use, for example:

~~~bash
mvn -pl emitter compile exec:java@cpu-cli \
  -Dexec.mainClass=br.unb.cic.td18.CountTD18Bracelets \
  -Dexec.args="--rows=50-204"
~~~

Optional --table and --log arguments select another input or output path. See
[the Burnside derivation](td18-burnside-counting.md) for the counting
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
[docs/td18-reduction-count.tex](td18-reduction-count.tex). Rows with
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

To run or resume only the persisted obstruction phase from the current database, use:

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
output, create the directory with `mkdir -p logs`, then append
`2>&1 | tee -a logs/checker-verification.log` to the command
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
mvn -pl checker compile exec:java \
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
