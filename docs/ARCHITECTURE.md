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
| emission | Table 2, exceptional oriented 9- and 15-cycle interleavings, and oriented 19-cycle emission. |
| verification | Checker inventory, Q audit, DFS classification, disk verification, and progress. |
| runtime | Repository path discovery. |

Existing root-package command names remain as small entry points where their
implementation moved. CountTD18Bracelets and Table2ProvenanceGraph are
command-line tools. Reusable code reads Table2 directly and uses ProjectPaths
for paths rather than depending on a counting command.

The emitter retains base/GPUSortingSearch.java and its GPU scheduling helpers
to preserve the native class names and method descriptors for sorting searches.
Avoidance reconstruction runs in parallel CPU workers and deduplicates canonical
class keys in the shared work store. Persisted formats are unaffected by package
organization.

## Independence is specific

The checker has its own work-list type enumeration and oriented 15- and
19-cycle classifications.
The inventory includes qualifying 2-move branches and separately accounts for
cited support-clearing alternatives within the global budget. Both
enumerations first apply known-diameter closure, then retain any totally
unoriented open type without structural outgoing edges. The even-pair
fallback requires an oriented cycle somewhere in the type, including when
both selected even-length cycles are unoriented. Among types with an
oriented cycle, the enumerations retain an early type when the remaining no-qualifying-move
case lacks a closing alternative or an available fallback. The provenance graph's
version 6 format distinguishes these closures from transposition edges and
allows retained early states to have outgoing qualifying branches.
It records the three restricted odd-pair rows as direct root-lifting routes:
their whole-type R values remain unchanged, while Q is 391, 321, and 1,172.
The exceptional-nine restriction uses the copied direct-generation primitive
with a prescribed rank progression. Tests compare it with independent small
interleavings and the established TD(16) extension counts; this is not a second
independently designed large-domain generator.
For length 15, the checker independently visits all 169,355 DFS nodes and
identifies the unique oriented survivor, the local candidate
`gamma = beta_15^8`. Its three-cycle local target prevents support clearing.
Direct generation fixes its rank progression and varies the companion
interleavings: 360 candidate bracelets yield 90 valid root classes, and
27 candidate bracelets yield 7 valid intermediate classes. CPU prefix
search and independent replay close 79 root classes within budget 10 and
all 7 intermediate classes within budget 9. The 11 remaining root classes
require complete ten-transposition witnesses. The dedicated branch does
not generate every class of the ambient annotated types.
The checker's avoidance reconstruction uses sequential insertions and its own scratch
sets, rather than trusting the emitter’s reconstruction work database.

For rows 267, 270, and 274, `model/RestrictedOddPair` defines the two-cycle
rank-progressions and accepts only the fixed-point-free 18-symbol types.
The generator constrains both colors during prefix construction. The checker
independently reruns the local DFS at lengths 5, 7, 9, 11, and 13, enumerates
all support subsets and allowed powers, and compares the complete 1,884-class
set with direct generation. The emitter sends these seeds directly to
avoidance with source budget nine. The checker regenerates them without
consulting stored residual markers and requires every admitted root to close
within ten transpositions. Its proof boundary is complete reconstruction and
replay, not the existence of a seed record or work manifest.

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
| obstructions | Raw instance keys with empty values. | Search misses or direct restricted root-lifting seeds; row/type/budget/path context is regenerated from Table 2 and the policy. |
| avoidance-intermediate-witnesses | Retained legacy namespace. | Compatibility only; the present route neither writes nor consumes it as proof input. |

The read-only checker requires all three families. The emitter accepts an
older compatible database containing default and obstructions and creates
the legacy intermediate family non-destructively when opening it. Both
reject the old prefixed single-family format. This reader/writer distinction
also applies when resuming a database for the restricted route.

CPU prefixes are not persisted as sorting certificates. Their fixed-point
gain, contracted domain, known diameter, and budget are regenerated and checked.
The emitter’s versioned sidecar row manifests use format 4, avoidance manifests
use format 7, and the avoidance work database uses format 5. They record
deterministic keys, counts, checksums, and completion state. Canonical-restoration
inverse-support counts precede parent-type filtering and class deduplication.
Standalone avoidance regenerates the restricted seeds even when their
obstruction records are absent. Mixed source budgets eight, nine, and ten
are preserved across reconstruction and resume; exact source-inventory
validation prevents old work from omitting newly required seeds or terminal
roots. Earlier formats are rejected because their source inventories,
completion semantics, or structural policy differ.

The exceptional-nine branch regenerates the class stream and replays every
full database witness within ten transpositions, rejecting missing witnesses
and exceeded budgets. Full emission and
avoidance ensure all 18 root annotations; the default checker includes this
branch. Focused emission/checking can select one type explicitly.
The exceptional-fifteen branch likewise regenerates both restricted families
and enforces each family's move budget. Its independent local classification
and checked generation counts establish the scope of that branch. Every
run regenerates and independently replays the 86 CPU prefix closures;
prefixes are not stored as complete certificates. Only the 11 root prefix
misses require database witnesses, whose complete checks remain pending.

The certificate database is the only emitter output consumed by the checker.
Existing Table 2 and avoidance sidecars are diagnostic metadata. Proof
verification uses exhaustive regeneration, witness replay, and checked closure
arguments without requiring sidecar files or checksums. A successful partial
run or a witness for one class does not establish the complete TD(18) claim.

Generated databases, scratch sets, certificates, checkpoints, and logs remain
untracked. The source document, explanatory documentation, and provenance graph
remain reviewable repository inputs/documentation. See [the workflow](WORKFLOW.md)
for output paths and stage completion requirements.
