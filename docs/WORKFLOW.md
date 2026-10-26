# Proof and computation workflow

The route is conditional and TD(18) = 10 remains unproved in this repository.
[The source document](td18-reduction-count.tex) specifies the argument;
[the research plan](RESEARCH_PLAN.md) specifies the checked artifacts needed
to complete it. Successful development commands do not replace those artifacts.

## Start on CPU

JDK 21, Maven, and Bash are sufficient for these commands. Run from the
repository root; the launcher also works when invoked by its path from a
module directory.

~~~bash
tools/td18 test
tools/td18 inventory
tools/td18 structure
~~~

inventory checks 53 admissible fixed-point-free root partitions, 360 annotated
roots, and the complete 292-type obligation inventory, with obligation depth
distribution 59, 45, and 188. structure uses the checker’s independent work-list
enumeration, checks the Q mapping, and reproduces the oriented 15- and
19-cycle classifications. It also independently classifies the local
5-, 7-, 9-, 11-, and 13-cycle failures and compares support enumeration
with the direct generator's exact 1,884 restricted odd-pair classes.
It also checks the six exceptional-nine companion partitions and 18 root
annotations, and the two exceptional-fifteen companion obligations. It does
not open a certificate database or replay witnesses.
At every depth, known-diameter closure is tested first. A remaining totally
unoriented type becomes a direct obligation with no structural children.
This stop is a routing choice, not a claim that transpositions cannot act.
The even-pair fallback requires some oriented cycle in the type; its selected
even-length cycles may both have unoriented annotations.
Rows 1–204 keep their original types and budgets; the remaining 88 rows are
51 root obligations and 37 one-move obligations. A cited support-clearing
alternative removes the need to retain the no-qualifying-move case only when
its full cost fits the global budget. The provenance graph displays that
closure separately from the qualifying 2-move children.
All types in the previous 225-type inventory remain present. The new
totally unoriented stop adds 67 direct obligations: 45 roots and 22 states
after one move. The first 204 row numbers and budgets are preserved.
The sole oriented 15-cycle without a qualifying transposition has a separate
interleaving branch: 90 root classes have budget 10 and 7 intermediate
classes have budget 9. These restricted classes do not retain the entire
intermediate type `(15O,3U)` in Table 2.
The checker regenerates CPU prefix closures for 79 roots and all seven
intermediate classes. Eleven root classes still require complete witnesses.

Use tools/td18 help to list commands and tools/td18 --dry-run COMMAND to inspect
the exact Maven invocation. Additional arguments are Maven options, passed
without shell evaluation. For example:

~~~bash
tools/td18 count -Dexec.args=--rows=130-134
tools/td18 verify '-Dexec.args=--phase=rows --rows=2-16'
~~~

## Follow the obligations

~~~mermaid
flowchart TD
    S[Source document and reduction hypotheses] --> I[292-type inventory]
    I --> C[Count R and verify the Q partition]
    C --> G[Generate one valid pair per Q class]
    G --> D[CPU prefix closure or bounded CUDA search]
    G --> P[Rows 267, 270, 274: 1,884 restricted seeds]
    P --> R
    D --> W[Complete sorting witnesses]
    D --> O[Terminal obstructions]
    O --> A[Inverse reconstruction and intermediate CPU screening]
    A --> R[Full-domain root closure or ten-move search]
    R --> W
    R --> U[Unresolved roots: proof remains pending]
    S --> E[Two exceptional oriented 19-cycle classes]
    E --> W
    S --> N[Fixed exceptional 9-cycle interleavings: 18 root types]
    N --> W
    S --> L[Fixed exceptional 15-cycle interleavings: 90 root and 7 intermediate classes]
    L --> W
    W --> V[CPU regeneration and witness replay]
    V --> F[Complete verification report: pending]
~~~

| Research stage | Tool or source | Output and what remains required |
| --- | --- | --- |
| 0: reduction hypotheses | Source document; structure for the implemented structural and DFS checks | Checked proofs/citations, both 19-cycle certificates, all exceptional-nine root extensions, and the remaining 11 exceptional-fifteen root witnesses are still required. |
| 1: finite inventory | inventory and structure | Agreeing enumerations of all 292 rows and a checked complete inventory report remain required. |
| 2: realizability and counts | count; CPU small-domain tests | R comparisons and a local count log; a versioned complete count artifact and independent report remain required. |
| 3: exhaustive generation | emit | Generation count must equal Q for every selected row; all required partitions and outcomes must be independently verified. |
| 4: sorting discharge | emit, avoidance, exceptional-nine, exceptional-fifteen, survivors, or import-survivors | Replayed complete witnesses or regenerated prefix/known-diameter closures; every residual root must be resolved. |
| 5: end-to-end verification | verify | CPU regeneration and witness replay; a complete run and verification report remain pending. |

Table 2 always contains all 292 types. R counts every realizable extended-toric
class. Q retains the obligations after the specified TD(16) reuse and the
restricted odd-pair route: 102 rows are discharged; rows 267, 270, and 274
have Q = 391, 321, and 1,172; the other 187 retain Q = R.
Thirty-five of the latter rows are empty, giving 137 literal
zero-Q rows. There are 46 empty types overall, including 11 settled rows.
Rows 130 and 134 retain Q = R in
ordinary table order. The recorded totals are R = 692,894,074,401 and
Q = 45,498,334,516; a new run checks them rather than treating them as
completed proof artifacts.

The three restricted rows retain all qualifying child branches. Their
1,884 classes have no qualifying move on either cycle and enter root
reconstruction directly, without nine-move search. At most 5,188,536 inverse
attempts precede filtering and root-class deduplication; every resulting
root must close by a checked CPU prefix or a full ten-move witness.
The checker regenerates these seeds even if their stored obstruction
markers are absent. Other rows' residual roots and the separate exceptional
families add work beyond the Table 2 count; see [the workload accounting](two-cycle-residual-route.md#workload-and-proof-completion).

## Run and resume computations

count uses CPU Burnside counting. The emit, avoidance, exceptional-nine,
exceptional-fifteen, and survivors launchers enable the gpu build profile
for native searches, requiring a configured CUDA environment. The seven
intermediate exceptional-fifteen classes close entirely on CPU; the
[operations guide](OPERATIONS.md#emit-and-check-exceptional-15-cycle-interleavings)
gives a direct CPU command for that family.
import-survivors replays an existing two-witness input on CPU. Full emission
and avoidance also ensure both 19-cycle exceptional certificates and every
exceptional-nine root extension witness are present. The latter branch has
18 types, with optional focused emission via -Dtd18.args=--type=9O-8U-2U and
focused checking via --phase=exceptional-nine --nine-type=9O-8U-2U.
The exceptional-fifteen branch regenerates 86 CPU prefix closures and
requires witnesses only for 11 remaining roots in `15O-2U-2U@0`. Select
the intermediate family `15O-3U@1`, whose classes all close on CPU, with
`-Dtd18.args=--type=15O-3U@1` for emission and
`--phase=exceptional-fifteen --fifteen-type=15O-3U@1` for checking. Full
emission, avoidance, and default verification include both families.
Explicit ten-transposition witnesses for both 19-cycle exceptions are documented in
[the knowledge guide](KNOWLEDGE.md#explicit-clearing-of-the-two-oriented-19-cycle-exceptions)
and independently replayed in checker tests; they need no new CUDA search.

~~~bash
tools/td18 emit -Dtd18.certificateDb="$PWD/certificates"
tools/td18 avoidance -Dtd18.certificateDb="$PWD/certificates" \
  -Dtd18.args="--work-database=$PWD/avoidance-work"
tools/td18 import-survivors -Dtd18.args=--witnesses=/path/to/witnesses.txt
tools/td18 verify -Dtd18.certificateDb="$PWD/certificates"
~~~

Consult [the operations guide](OPERATIONS.md) before selecting row ranges,
changing device/memory settings, or resuming existing work. Row subsets and
selected verification phases cover only those obligations. An avoidance-only
check reconstructs the selected residual routes and always regenerates the
restricted odd-pair seeds; it does not repeat all other Table 2 generation.

## Know where outputs belong

| Path | Producer | Consumer and role |
| --- | --- | --- |
| certificates/ | Emitter tools | Read-only CPU checker; raw witness keys/values and key-only obstructions. |
| certificates/proof-manifests/ | Emitter tools | Diagnostic Table 2 and avoidance metadata; not required by the checker. |
| avoidance-work/ | Avoidance runner | Resume state for the emitter; the checker independently reconstructs its own sets. |
| checker/target/verification-work-* | Checker | Fresh scratch sets for disk deduplication. |
| td18-bracelet-counts.log | Counter | Development count log; does not meet all stage 2 artifact requirements. |
| logs/ | Local command runs | Diagnostic output; not a verification report by itself. |

These computational outputs remain untracked. Existing formats, deterministic
keys, and checksums are described in [the architecture](ARCHITECTURE.md).

## Next work required for completion

The checker consumes the certificate database and regenerates all selected
obligations. Checksums and sidecar manifests are not proof inputs. Produce
complete checked inventory/count artifacts, then run
the exhaustive computation and independent checker over every required class,
avoidance branch, and exceptional certificate. Stages 0–5 remain open until
their individual exit criteria have checked artifacts.
