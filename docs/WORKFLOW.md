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
roots, and the complete 204-type obligation inventory, with terminal depth
distribution 8, 8, and 188. structure uses the checker’s independent work-list
enumeration, checks the Q mapping, and reproduces the oriented 19-cycle DFS.
It does not open a certificate database or verify the two survivor witnesses.

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
    S[Source document and reduction hypotheses] --> I[204-type inventory]
    I --> C[Count R and verify the Q partition]
    C --> G[Generate one valid pair per Q class]
    G --> D[CPU prefix closure or bounded CUDA search]
    D --> W[Complete sorting witnesses]
    D --> O[Terminal obstructions]
    O --> A[Inverse reconstruction and intermediate CPU screening]
    A --> R[Full-domain root closure or ten-move search]
    R --> W
    R --> U[Unresolved roots: proof remains pending]
    S --> E[Two exceptional oriented 19-cycle classes]
    E --> W
    W --> V[CPU regeneration and witness replay]
    V --> F[Final artifact integrity and report: pending]
~~~

| Research stage | Tool or source | Output and what remains required |
| --- | --- | --- |
| 0: reduction hypotheses | Source document; structure for the implemented structural and DFS checks | Checked proofs/citations for every premise and both replayed exceptional certificates are still required. |
| 1: finite inventory | inventory and structure | Agreeing enumerations of all 204 rows; a checked complete inventory checksum artifact remains required. |
| 2: realizability and counts | count; CPU small-domain tests | R comparisons and a local count log; a versioned complete count artifact and independent report remain required. |
| 3: exhaustive generation | emit | Generation count must equal Q for every selected row; all required partitions and completion records must be independently verified. |
| 4: sorting discharge | emit, avoidance, survivors, or import-survivors | Replayed complete witnesses or regenerated prefix/known-diameter closures; every residual root must be resolved. |
| 5: end-to-end verification | verify | CPU checks of selected obligations; final sidecar checksum/completion enforcement and a complete report remain pending. |

Table 2 always contains all 204 types. R counts every realizable extended-toric
class. Q retains the obligations after the specified TD(16) reuse: 102 rows
are discharged and the other 102 retain Q = R. Fourteen of those latter rows
are empty, giving 116 literal zero-Q rows. Rows 130 and 134 retain Q = R in
ordinary table order. The recorded totals are R = 33,544,626,415 and
Q = 29,064,174,153; a new run checks them rather than treating them as
completed proof artifacts.

## Run and resume computations

count uses CPU Burnside counting. emit, avoidance, and survivors require a
configured CUDA environment and explicitly enable the gpu build profile.
import-survivors replays an existing two-witness input on CPU. Full emission
and avoidance also ensure both exceptional certificates are present.
Explicit ten-transposition witnesses for both exceptions are documented in
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
check does not repeat the original Table 2 generation.

## Know where outputs belong

| Path | Producer | Consumer and role |
| --- | --- | --- |
| certificates/ | Emitter tools | Read-only CPU checker; raw witness keys/values and key-only obstructions. |
| certificates/proof-manifests/ | Emitter tools | Versioned row/range sidecars; final independent integrity enforcement is pending. |
| avoidance-work/ | Avoidance runner | Resume state for the emitter; the checker independently reconstructs its own sets. |
| checker/target/verification-work-* | Checker | Fresh scratch sets for disk deduplication. |
| td18-bracelet-counts.log | Counter | Development count log; does not meet all stage 2 artifact requirements. |
| logs/ | Local command runs | Diagnostic output; not a verification report by itself. |

These computational outputs remain untracked. Existing formats, deterministic
keys, and checksums are described in [the architecture](ARCHITECTURE.md).

## Next work required for completion

The current checker intentionally allows development runs without enforcing
sidecar completion records or comparing saved fingerprints. This is an
explicit implementation gap against stage 5, also recorded in the source
document and research plan. The next verification work is to implement that
enforcement and produce complete checked inventory/count artifacts, then run
the exhaustive computation and independent checker over every required class,
avoidance branch, and exceptional certificate. Stages 0–5 remain open until
their individual exit criteria have checked artifacts.
