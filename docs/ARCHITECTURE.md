# Implementation and artifact boundaries

The Maven reactor has two independent Java artifacts. emitter generates
obligations, runs CPU/CUDA searches, and writes witnesses. checker is CPU-only,
opens the certificate database read-only, regenerates its obligations, and
replays witnesses. Neither artifact depends on the other.

The parent POM owns JDK 21, dependency versions, annotation processing, and
test-plugin versions. Module POMs own launch configuration and the emitter’s
explicit gpu build profile. CPU builds do not configure CMake or load CUDA.
The emitter uses exec:java@cpu-cli for in-process CPU commands and exec:exec
for the external JVM with JNI options. Keeping their execution configurations
separate prevents JNI launch arguments from being interpreted as CPU tool
arguments.

## Packages follow responsibilities

All packages below have the br.unb.cic.td18 prefix. Tests follow the same
package layout as the implementation they exercise.

| Package | Responsibility |
| --- | --- |
| permutation | Cycles, algebraic permutations, and group operations. |
| model | Candidates, validated pairs, encoding words, and immutable orbit/class keys. |
| bracelet | Direct fixed-point-free generation and exact Burnside counting. |
| reduction | Partitions, annotations, known diameters, and the two-reduction policy. |
| table | Parsing the source document and immutable Table 2 row metadata. |
| search | CPU prefix checks and search outcomes; emitted bounded searches. |
| certificate | Raw encoding, witness replay, database access, and emitter manifests. |
| avoidance | Residual obligations and inverse reconstruction; emitter work persistence and orchestration. |
| emission | Table 2 and exceptional oriented 19-cycle emission. |
| verification | Checker inventory, Q audit, DFS classification, disk verification, and progress. |
| runtime | Repository path discovery. |

Existing root-package command names remain as small entry points where their
implementation moved. CountTD18Bracelets and Table2ProvenanceGraph are
command-line tools. Reusable code reads Table2 directly and uses ProjectPaths
for paths rather than depending on a counting command.

The emitter retains base/GPUSortingSearch.java and its GPU scheduling helpers,
plus the root GpuAvoidanceReconstructor JNI entry point, to preserve the native
class names and method descriptors. The avoidance implementation delegates to
that bridge. Native source and persisted formats are unaffected by package
organization.

## Independence is specific

The checker has its own work-list type enumeration and oriented 19-cycle DFS.
Its avoidance reconstruction uses sequential insertions and its own scratch
sets, rather than trusting the emitter’s reconstruction work database.

Some framework code is copied into both modules: permutation operations,
encoding and keys, the Burnside counter, and substantial direct-generation
code. The two artifacts are independent at build time; this does not mean
every mathematical primitive has two independently designed implementations.
The checker’s independent inventory and reconstruction paths remain explicit.
Removing copied code requires a deliberate decision about the verification
trust boundary, beyond a package reorganization.

## Persistence preserves proof roles

The certificate database uses three column families:

| Family | Records | Proof role |
| --- | --- | --- |
| default | Raw five-bit instance keys and fifteen-bit transposition triples. | Complete Table 2 or full-domain-root witnesses, checked by replay. |
| obstructions | Raw instance keys with empty values. | Search misses; row/type/budget/path context is regenerated from Table 2 and the policy. |
| avoidance-intermediate-witnesses | Retained legacy namespace. | Compatibility only; the present route neither writes nor consumes it as proof input. |

Older databases containing only default and obstructions are rejected by both
the current reader and writer. Inspect the schema before resuming a local
database; importing witnesses into a fresh compatible database is a separate
operation from verifying an older artifact.

CPU prefixes are not persisted as sorting certificates. Their fixed-point
gain, contracted domain, known diameter, and budget are regenerated and checked.
The emitter’s versioned sidecar row manifests use format 3, avoidance manifests
use format 4, and the avoidance work database uses format 2. They record
deterministic keys, counts, checksums, and completion state. Canonical-restoration
inverse-support counts precede parent-type filtering and class deduplication.
Unsupported work/manifest formats are rejected where those artifacts are read.

The current development checker does not enforce sidecar completion records or
compare saved fingerprints. The sidecars’ existence alone therefore does not
establish final artifact integrity. This gap is recorded in
[the research plan](RESEARCH_PLAN.md) and [the source document](td18-reduction-count.tex).
Do not turn a successful partial run or a witness for one class into a complete
TD(18) claim.

Generated databases, scratch sets, certificates, checkpoints, and logs remain
untracked. The source document, explanatory documentation, and provenance graph
remain reviewable repository inputs/documentation. See [the workflow](WORKFLOW.md)
for output paths and stage completion requirements.
