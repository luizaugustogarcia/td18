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
--database=PATH, --work=PATH, and --td16-db=PATH. The default phase is all.
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
nine moves and sends remaining full roots to budget-ten search. Both searches
try the bounded CPU prefix before dispatching to CUDA.
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

The GPU search checks identity and the known-diameter bound at every depth
up to the obligation budget. It permits odd-length-cycle gains zero and two.
A CPU prefix first tries the same exits, using actual applicable transpositions
with odd-length-cycle gains zero and two. Its defaults are depth three and
8,192 examined supports per obligation. Set td18.cpuPrefixDepth=0 or
td18.cpuPrefixMaxTriples=0 to disable prefix expansion; initial identity and
diameter checks still apply. Neither fixed-point gain ordering nor the prefix
limits remove any full GPU obligation. Totally unoriented states receive no
structural reduction. Every successful prefix is replayed and persisted.

Every 10 seconds, GPU statistics report searches per second, complete
witnesses, diameter closures, misses, slot occupancy, pending work, and
failures. Phase progress is logged separately. A throughput of 55.727/s means
about 56 searches per second.
CPU prefix statistics separately report attempts per second, settled results,
GPU fallthroughs, closure rate, and average search time and prefix depth.
td18.cpuStatsPeriodSeconds controls their period and defaults to the GPU period;
zero disables a reporter. GPU throughput counts only fallthroughs once CPU
prefixes are enabled, so use both counters when comparing runs.

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
The queues and disk buffer have these defaults:

| Buffer | Capacity | Configuration |
| --- | ---: | --- |
| Pending certificate writes | 16,384 entries | td18.certificateQueueCapacity |
| Pending oriented-root search tasks | 256 tasks | Fixed bound in the emitter |
| Buffered unique keys per disk set | 16,384 keys | Fixed bound in emitter and checker |

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
