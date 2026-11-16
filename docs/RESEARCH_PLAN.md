# Evidence checklist for the proof of TD(18) = 10

The [source paper](td18-reduction-count.tex) presents a computer-assisted proof
of TD(18) = 10: checked exhaustive coverage supplies the upper bound and the
reverse permutation supplies the matching lower bound. This checklist records
the mathematical and computational evidence required by that proof, using the
two-sided Eriksson-plus-third-move route. The smaller established diameters, including the separately certified TD(16) = 9, remain premises.
Counts and passing tests alone do not establish the upper bound.

## 1. Coverage and small domains

Check the source paper's endpoint-side triangle inequality, fixed-point
contraction, exhaustive gain-zero/one first supports, gain-at-least-two
characterization, reverse exception and explicit budget-valid support patterns.
The two inverse branches are `(1,2,2)` and `(0,3,2)`, on both endpoints.
The former first-move qualifying restriction is not an active premise.

Exit artifacts: the source coverage derivation and the passing
`ActiveErikssonRouteTest` and `ActiveErikssonInverseTest` suites in both modules.
The tests compare complete small-domain class sets and all inverse gains zero
through three against arbitrary full-cycle support enumeration.

## 2. Independent active census

Regenerate all 53 root partitions in both modules. The source inventory records
**2 direct root classes** after support clearing; both modules must regenerate
and check every row for the current run.
All 74 annotated endpoint types must be regenerated structurally; each
non-elementary generated class count is independently checked against Burnside.
The endpoint cover contains all 74 even fixed-point-free annotated types on 14 symbols, with budget 7.

Exit artifacts: every versioned report in `data/eriksson-root-counts-v1/` and
`data/eriksson-root-counts-checker-v1/`, matching the active paper rows, and the
complete structural endpoint cover. Endpoint census reports, where counted,
are under the corresponding `eriksson-endpoint-counts[-checker]-v1` directories.
All pending, missing, incompatible or malformed required data must be rejected.

## 3. Sorting obligations

Each active direct class needs a persisted complete witness or valid diameter
closure within 10 moves. Each non-elementary endpoint needs a checked
result within 7 moves or regenerated inverse coverage. Intrinsic results are
replayed at the explicit requested budget. A longer valid witness is a miss
for a smaller budget; malformed data are errors. A bounded CPU or GPU miss is
not a lower bound and cannot close a class.

Exit artifacts: replayable results in the certificate database for every direct
class, and endpoint discharges or the full regenerated residual set. No sorting
failure count is assumed in advance.

## 4. Complete inverse coverage

Reverse the third gain-two move, then both possible second moves, then the
corresponding first move. The after-two budget is 8; the after-one
budget is 9; every remaining root needs budget 10. Restore all
fixed symbols in all gaps, reverse every compatible support on both endpoints,
and verify actual forward gains. Use disk-backed class deduplication and bounded
persistence. Missing obstruction markers cannot omit any obligation.

Exit artifact: a complete independently regenerated inverse report with no
unresolved root. If all endpoints have diameter closures, its residual set and
all inverse waves are empty; the full checker must still report that scope.

## 5. End-to-end verification

Run `tools/td18 verify --phase=all` against the completed database, with no
range omissions and fresh checker work. It regenerates all roots and endpoint
obligations, replays witnesses, and independently rebuilds the three inverse
waves. Emitter checkpoints, checksums and sidecar manifests are not proof inputs.

Exit artifacts: both full required Maven CPU suites and one successful complete
checker report, together with the counted inventories and replayed database.
All five criteria require checked artifacts in the proof record. The reference
emission logs document a reproduction; they do not replace independently
regenerated inventories or replayed certificates. CUDA tests require a configured
CUDA environment; a proof needing only CPU certificates does not require GPU use.

## Reference reproduction and publication status

A full independent checker run has already succeeded. The retained reference
reproduction is the local Intel Core Ultra 9 185H / RTX 4070 Laptop emission
of 16 November 2026, completed from 02:57:32 to 07:35:21 Lisbon time in
**4 h 37 min 49 s**, with exit 0 and no unresolved reconstructed root.
It covered all 53 root partitions, 74 endpoint annotations, 2 direct root
classes, 61,798,462 generated non-elementary endpoints, and 1,349,910,890
final reconstructed root classes. Its configuration, exact timestamps, phase
durations and completed logs are in [REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md).

The separate-machine reference verification completed on an AMD Ryzen 7 8840HS
with 16 workers and a 6 GiB maximum Java heap, on 16 November 2026 from
08:25:38 to 10:11:21 Lisbon time, in **1 h 45 min 43 s**, with exit 0.
It independently regenerated and checked all active root rows, the 74-annotation
endpoint cover and 61,798,462 non-elementary endpoint classes, then rebuilt the
inverse waves: 19 residual endpoints, 25,544 after-two classes, 6,451,327
after-one classes and 1,349,910,890 final roots, with complete replay coverage.
Its full-scope success message, complete logs and runtime settings are retained
under `logs/reference/` and described in [the reference record](REFERENCE_SIMULATION.md).
Tests were skipped for this run at the operator's request, and standalone census
reports were not republished. The counted census reports and test artifacts
specified above remain separate checklist requirements; this verification does
not label those unproduced artifacts checked.
Only this reference reproduction's emission and verification run artifacts are
retained in the local checkout. See [validation](VALIDATION.md) for the complete
independent verification procedure.
