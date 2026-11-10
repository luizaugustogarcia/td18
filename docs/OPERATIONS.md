# Computational operations

Use [the workflow](WORKFLOW.md) for stage order and
[the research plan](RESEARCH_PLAN.md) for completion criteria. Run commands
from the repository root. JDK 21, Maven, and Bash are required; counting and
checking run on CPU. CUDA is needed only for native sorting searches.

## Inventory and CPU validation

~~~bash
mvn -pl emitter -am test
mvn -pl checker -am test
python3 -B -m unittest discover -s tools/audit -p 'test_*.py'
tools/td18 inventory
tools/td18 structure
~~~

The inventory has 53 root partitions and 104 endpoint annotations, including
52 elementary endpoint closures. Structural checks and tests do not establish
the complete sorting bound. Build the native library on a configured machine
with tools/td18 build-gpu. Use tools/td18 --dry-run COMMAND to inspect a launcher
command before running it.

## Counting with all available CPU cores

~~~bash
tools/td18 count -Dexec.args=--phase=roots
tools/td18 count -Dexec.args=--phase=endpoints
~~~

The default phase is all. Set --workers=N to limit parallel counting. Restrict
a root count with --partitions=FIRST-LAST, or endpoint count with
--endpoints=FIRST-LAST. Select a paper with --paper=PATH. For example:

~~~bash
tools/td18 count '-Dexec.args=--phase=roots --partitions=1-1 --workers=4'
~~~

Root row 1 is partition (19), with 667 oriented residual classes and no
totally unoriented or support-cleared classes. Full root counts must agree
with every paper row. The completed total is 248,082,611 direct roots;
additional final inverse work is not included. Production Java regeneration
has reproduced every oriented residual, support-clearing, and remaining
oriented count in all 53 partitions; the complete artifact is
data/joint-reduction-audit/java-production-counts-v1.jsonl.

The independent native audit uses a C++20 compiler. It defaults to the CPU
cores available to the process and schedules one partition per worker.
Use a fresh output directory for each run; partial partitions cannot yield
a completed total. These commands write untracked artifacts:

~~~bash
c++ -O3 -std=c++20 tools/audit/count_joint_residuals.cpp -o /tmp/td18-joint-count
c++ -O3 -std=c++20 tools/audit/count_joint_clearings.cpp -o /tmp/td18-joint-clearings
/tmp/td18-joint-count --verify-small=10
/tmp/td18-joint-clearings --self-test
python3 -B tools/audit/verify_local_cycles.py
python3 -B tools/audit/run_joint_counts.py --n=19 \
  --representatives --merge-representatives \
  --output=data/joint-reduction-audit/native-recount
/tmp/td18-joint-clearings data/joint-reduction-audit/native-recount/partition-*.txt \
  > data/joint-reduction-audit/native-recount/support-clearings.jsonl
python3 -B tools/audit/summarize_joint_counts.py \
  data/joint-reduction-audit/native-recount/counts.json \
  --clearings=data/joint-reduction-audit/native-recount/support-clearings.jsonl \
  --output=data/joint-reduction-audit/recount-summary-v2.json
~~~

For a complete independent Java class-set comparison on a small domain:

~~~bash
/tmp/td18-joint-count --n=10 --representatives=/tmp/td18-joint-n10.txt
java --class-path emitter/target/classes tools/audit/CheckJointResiduals.java \
  --read=/tmp/td18-joint-n10.txt
~~~

For larger listed sets, the Java tool requires --verify-only. That checks
membership, realizability, and uniqueness of listed classes; it does not
independently prove that a large list contains every required class.

## Emission and final reconstruction

WitnessEmitter accepts --phase=roots|endpoints|avoidance|all,
--partitions=FIRST-LAST, --endpoints=FIRST-LAST, --paper=PATH,
--database=PATH, --work=PATH, --td16-db=PATH, and --resume-work=PATH. The default phase is all.
The database defaults to certificates/ or the td18.certificateDb property;
--database overrides that property. Work directories are fresh per invocation
and must lie outside the certificate database. An explicit --work path must
not already exist. On a configured CUDA machine:

~~~bash
tools/td18 emit -Dtd18.certificateDb="$PWD/certificates" \
  -Dtd18.args=--phase=roots
tools/td18 emit -Dtd18.certificateDb="$PWD/certificates" \
  '-Dtd18.args=--phase=endpoints --td16-db=/path/to/td16-certificates'
tools/td18 avoidance -Dtd18.certificateDb="$PWD/certificates"
~~~

The optional TD(16) database is read-only. Only valid complete witnesses
within the requested budget are reused. Roots use ten moves; endpoints use
eight. Final reconstruction searches 18-symbol predecessors on GPU within
nine moves. Both reconstructed intermediates and roots first use the bounded
CPU prefix for at most td18.reconstructionCpuPrefixMillis, ten milliseconds by
default. A miss or timeout sends the untouched obligation and full remaining
budget to the GPU.
Missing endpoint witnesses generate final obligations even if no obstruction
marker exists. No list of expected endpoint failures is required.

Joint root generation and root witness submission each use
Runtime.getRuntime().availableProcessors() workers automatically. The joint
generator divides disjoint prefix subtrees among those workers. Unoriented
root and endpoint generation use td18.twistedBraceletWorkers=auto by default,
which adds the configured GPU slots to the available CPU count so that waiting
for CUDA does not leave CPU workers idle. No server-specific CPU count is
required. Joint-generation and root-witness queues each hold at most 256
tasks; a full queue makes the submitting thread process work itself.

Final inverse generation uses all available CPU cores. Intermediate and
reconstructed-root searches use the same td18.twistedBraceletWorkers count as
direct generation, with bounded queues feeding concurrent GPU searches.
Canonicalization runs outside the disk-set write lock. Each wave
finishes all callbacks before its deduplicated successor set is sealed;
worker failures prevent successful completion. Reconstruction progress reports
completed and total classes separately for the intermediate and root waves.

Reconstruction buffers up to 1,048,576 unique canonical keys per disk set,
configured with td18.reconstructionBufferKeys. RocksDB deduplicates across
all flushed buffers. Before dispatch, the intermediate and root waves perform
multiGet lookups in batches of 262,144 classes, configured with
td18.certificateLookupBatchSize. Every returned witness is replayed and
checked against that wave's budget. Cache hits are counted as completed and
never enter the sorting worker queue; only misses are submitted. Each miss
still receives the ordinary fresh lookup when its worker starts, so a witness
written after the bulk read can also avoid search. Final partial batches and
missing records remain part of the obligation inventory. Progress reports
cached classes separately, and each bulk lookup logs requested and reusable
counts. Both buffer sizes must be positive and keep memory use bounded.

Reconstruction CPU work is generation, canonicalization, disk deduplication,
batched certificate lookup, replay, and the time-bounded prefix for both
reconstructed waves. Set td18.reconstructionCpuPrefixMillis=0 to perform only
the initial prefix checks before GPU fallback. The former CPU helper scan is
not used.
The GPU retains identity and known-diameter exits at every search depth.

After sealing the intermediate wave, emission writes
reconstruction-checkpoint.txt with a version and exact endpoint and
intermediate counts. After sealing the root wave, it additionally writes
root-reconstruction-checkpoint.txt with the propagated-intermediate and root
counts. Resume the latest sealed wave without repeating earlier work:

~~~bash
tools/td18 avoidance -Dtd18.certificateDb="$PWD/certificates" \
  '-Dtd18.args=--resume-work=/path/to/previous/work'
~~~

The checkpoint databases are opened read-only; every record is decoded and
the exact inventory counts are checked. Missing databases, records, inventory
fields, or incompatible budgets fail the resume. An intermediate checkpoint
regenerates roots in the fresh work directory. A root checkpoint searches its
sealed root set directly; available sorting witnesses are replayed in both
cases. Preserve the original checkpoint directory for further resumes. Resume accepts no phase
other than avoidance, ranges, or TD(16) import. Checkpoints are emission
optimizations, not proof inputs: the independent checker still regenerates
all obligations and replays the certificate database.

The GPU search checks identity and the known-diameter bound at every depth
up to the obligation budget. It permits odd-length-cycle gains zero and two.
For direct roots and endpoints, a CPU prefix first tries the same exits, using actual applicable transpositions
with odd-length-cycle gains zero and two. Its defaults are depth three and
a shared support budget of 8,192 per obligation. Set td18.cpuPrefixDepth=0 or
td18.cpuPrefixMaxTriples=0 to disable prefix expansion; initial identity and
diameter checks still apply. Neither fixed-point gain ordering nor the prefix
limits remove any full GPU obligation. Totally unoriented states receive no
structural reduction. Every successful prefix is replayed and persisted.

The prefix follows positive-gain moves before general gain-zero setup moves.
After virtual contraction of fixed symbols, the paper's consecutive-triple
lemma lists every gain-at-least-two support as
(a, omega(a), omega squared(a)), using O(N) support tests. These moves are
followed even when further prefix moves are needed. Each gain-one support
has a unique agreeing algebraic edge and can be listed as
(a, omega(a), c), using O(N^2) support tests. Only gain-zero moves need the
general cubic scan. These are bounds on support enumeration at a node, not
on the full search. The search visits children as it finds them, rather than
spending its shared support budget collecting an entire candidate list.
The budget counts applicable consecutive supports and examined candidates
in the other scans; linear consecutive-support screening runs at each node.
It also rejects prefixes whose 3-norm exceeds the remaining move budget.
The GPU checks the O(N) consecutive supports for an immediate complete or
known-diameter exit before expanding general children. These search choices
add no structural reductions, including on totally unoriented states.

CUDA general-support enumeration contracts fixed symbols, while retaining
their original current-cycle positions in every recorded move. Cycle labels,
ranks, and lengths are computed once per parent, so each support's
odd-length-cycle delta uses constant-time arithmetic instead of repeated
cycle walks. At the final depth, the consecutive-support test covers every
possible exit: with one move remaining, a parent with at most three nonfixed
symbols would already close; a new diameter-zero closure therefore gains at
least two fixed points. Without diameter closures, a move reaching identity
fixes its three symbols. No general child can be queued at that depth.
Validate the cycle arithmetic independently on CPU with:

~~~bash
c++ -O3 -std=c++17 tools/audit/check_cycle_metadata.cpp -o /tmp/td18-cycle-metadata
/tmp/td18-cycle-metadata
~~~

This checks every permutation and ordered support through eight symbols,
and deterministic samples through nineteen; it does not certify the full
sorting obligations. GPU oracle tests additionally compare complete bounded
search results through six symbols, with and without diameter closures.

Every 10 seconds, GPU statistics report searches per second, complete
witnesses, diameter closures, misses, slot occupancy, pending work, and
failures. Phase progress is logged separately. A throughput of 55.727/s means
about 56 searches per second.
CPU prefix statistics separately report attempts per second, settled results,
GPU fallthroughs, closure rate, and average search time and prefix depth.
td18.cpuStatsPeriodSeconds controls their period and defaults to the GPU period;
zero disables a reporter. For direct roots and endpoints, GPU throughput
counts only fallthroughs once CPU prefixes are enabled, so use both counters
when comparing those phases. The same applies to reconstructed-class cache
misses after enabling the timed CPU prefix; cache hits remain separate.

If physical GPU utilization and slot occupancy are both near 100%, with a
full pending queue, increasing worker counts or lookup batches cannot feed
additional work to the current kernel. Compare identical intermediate
classes and budgets before changing slot counts, and replay every successful
result. Long unsuccessful budget-nine searches can dominate reconstruction
even when most other intermediates close quickly. Rates measured on a small
sample or another GPU are performance experiments, not a full-run forecast.

For a deterministic sample comparison of the search algorithm, build the
classes and a dependency classpath, then run the audit benchmark:

~~~bash
mvn -pl emitter compile dependency:build-classpath \
  -Dmdep.outputFile=target/benchmark-classpath.txt
java -Dtd18.cpuStatsPeriodSeconds=0 \
  --class-path "emitter/target/classes:$(cat emitter/target/benchmark-classpath.txt)" \
  tools/audit/BenchmarkWitnessSearch.java 4,3,3,3,2,2,2 10000 1 unoriented prefix
~~~

Arguments are PARTITION SAMPLES THREADS, followed by optional
joint|unoriented and pipeline|prefix (defaults: joint and pipeline).
The prefix mode replays every successful result and reports misses without
loading CUDA or writing a database. The pipeline mode searches and persists
to a fresh temporary database; it requires the native library on the Java
library path if any prefix misses. Add
-Djava.library.path=emitter/target/native on a configured CUDA machine.
Sampling and canonicalization are timed separately from search. Up to 2,000
sampled classes are used for warmup and excluded from the timed result.
Compare identical partitions, sample sizes, worker counts and VM properties.
Use both unoriented and joint scopes: 214,474,050 of the 248,082,611 direct
roots are totally unoriented. A deterministic prefix is not a random sample
or an exhaustive sorting check; its rate cannot predict the full run.

To monitor a complete run through a log file:

~~~bash
mkdir -p logs
tools/td18 emit '-Dtd18.args=--phase=all' > logs/emission.log 2>&1 &
tail -f logs/emission.log
~~~

Rerun the same phase and ranges to regenerate work and reuse valid stored
witnesses. A partial range is not a completed proof. All inverse gap and support
choices must be retained; class deduplication is disk-backed. A root miss
remains unresolved and prevents a successful completion claim.

Useful CUDA properties are td18.gpuDevices, td18.gpuSlots,
td18.gpuBudgetMB, td18.gpuQueueCapacity, and td18.gpuStatsPeriodSeconds.
For example:

~~~bash
tools/td18 emit -Dtd18.gpuDevices=0 -Dtd18.gpuSlots=1 \
  -Dtd18.gpuBudgetMB=1024 '-Dtd18.args=--phase=roots --partitions=1-1'
~~~

CUDA initialization is lazy. A count, inventory, structural check, or CPU
certificate replay does not initialize a sorting device.

## Certificate format and independent checking

The RocksDB schema has two column families. The default family reserves the
UTF-8 entry td18-schema = joint-selection-v2. Other entries map raw canonical
instance keys to complete witnesses or known-diameter closures. The obstructions family stores raw keys
with empty values. Keys encode symbols with five bits and implicitly use
the standard current cycle; each move is encoded by three five-bit symbols.
Pairs must be canonicalized before persistence. Result values start with a
kind byte (0 complete, 1 diameter closure) and an exact move-count byte,
followed by fifteen-bit symbol triples with zero padding. Zero-move results
have a two-byte value; counts above ten, unknown kinds and malformed payloads
are rejected. Existing databases with a different schema marker are rejected.
Use a fresh database path for this format; independently supplied TD(16)
complete witnesses remain readable through --td16-db.

Every result is replayed before acceptance. Complete records must reach
identity. Closure records must satisfy the actual move count plus the known
diameter after contraction. A valid result needing a larger budget is a miss
at a smaller budget; malformed or incorrect records reject.

The checker uses all processors available to its JVM by default.
td18.checkerWorkers sets the independent joint-generation, inverse-generation,
and certificate-replay worker count; td18.twistedBraceletWorkers separately
sets direct twisted-bracelet generation. Independent oriented-root generation
splits disjoint rank-placement prefix subtrees across the checker workers.
Certificate reads use RocksDB multiGet in batches of 262,144, configured by
td18.certificateLookupBatchSize. Each logical batch is sharded across all
checker workers for concurrent native multiGet calls, and the returned records
are then replayed in parallel on the same bounded pool.
Up to td18.certificateLookupPipelines batches overlap generation (four by
default). The independent
inverse waves use the same large lookup batches and bounded parallel callbacks.
td18.checkerStatsPeriodSeconds controls progress output and defaults to 60.
The queues and disk buffer have these defaults:

| Buffer | Capacity | Configuration |
| --- | ---: | --- |
| Pending certificate writes | 16,384 entries | td18.certificateQueueCapacity |
| Pending oriented-root search tasks | 256 tasks | Fixed bound in the emitter |
| Buffered unique keys per emitter disk set | 1,048,576 keys | td18.reconstructionBufferKeys |
| Buffered unique keys per checker disk set | 1,048,576 keys | td18.reconstructionBufferKeys |
| Certificate lookup batch | 262,144 classes | td18.certificateLookupBatchSize |

The certificate writer drains under queue pressure and flushes every five
seconds by default; td18.certificateFlushSeconds controls the period. The
root task queue applies backpressure when full, and disk sets flush their
key buffers into RocksDB. These bounds keep the generated obligation stream
from accumulating in an unbounded application queue. Obstruction markers
are work records, not lower-bound proofs. Operational work sets and reports do not
replace independent regeneration, and checksums or sidecar manifests are not
required proof inputs.

~~~bash
tools/td18 verify -Dtd18.certificateDb="$PWD/certificates"
tools/td18 verify -Dtd18.certificateDb="$PWD/certificates" \
  '-Dexec.args=--phase=roots --partitions=1-1'
~~~

CertificateChecker accepts matching phases and ranges, --paper, --database,
--work, and --structural-only. Its work directory is also fresh and outside
the certificate database. Endpoint-only verification reports residuals for
final reconstruction; a complete all-phase check must cover them. Full checking regenerates classes and inverse waves, checks counts, replays
stored complete witnesses and diameter closures. It rejects missing
required discharges or unresolved roots. Selected phases and ranges establish
only their selected scope. See the research plan before making a TD(18) claim.

## Build the paper

~~~bash
mkdir -p /tmp/td18-paper-build
pdflatex -interaction=nonstopmode -halt-on-error \
  -output-directory=/tmp/td18-paper-build docs/td18-reduction-count.tex
pdflatex -interaction=nonstopmode -halt-on-error \
  -output-directory=/tmp/td18-paper-build docs/td18-reduction-count.tex
cp /tmp/td18-paper-build/td18-reduction-count.pdf docs/td18-reduction-count.pdf
~~~

The source table contains exactly 53 machine-readable jointrow commands.
Regeneration and certificate checking enforce its inventory. Generated count
files, databases, checkpoints, logs, and certificates remain untracked.
