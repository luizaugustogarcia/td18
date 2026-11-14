# Active Eriksson-route workflow

The [source paper](td18-reduction-count.tex) defines the active route.
The emitter count is complete: **2 direct root classes**, after support clearing. Independent checker regeneration matches every partition. The endpoint cover contains all 74 even fixed-point-free annotated types on 14 symbols, with budget 7.

## Validate and count

```bash
tools/td18 build
tools/td18 test
tools/td18 inventory
tools/td18 count-roots
tools/td18 audit-roots
tools/td18 count-endpoints
tools/td18 audit-endpoints
tools/td18 endpoint-table
```

The root census runs all 53 partitions through JShell using all available
CPU threads. It atomically persists versioned per-partition reports under
`data/eriksson-root-counts-v1/`, then publishes every active paper row together.
The independent checker uses `data/eriksson-root-counts-checker-v1/` and compares
all counts against those rows. Repeated, malformed, incompatible or missing
fields cannot supply a completed count. Pending active rows allow structural
checks but block emission and proof verification.
Endpoint counting uses the full 74-annotation cover. Reports under
`data/eriksson-endpoint-counts-v1/` and
`data/eriksson-endpoint-counts-checker-v1/` include the route, domain, budget,
orientations, counts and elementary-closure flag. No additional 3-norm
restriction is imposed. A count does not discharge a sorting obligation.

## Emit and reconstruct

```bash
tools/td18 emit --phase=roots
tools/td18 emit --phase=endpoints
tools/td18 avoidance
```

Direct roots use budget 10; endpoints use 7. Checked intrinsic witnesses
can be reused only when their actual required budget fits the requested one.
Use `--source-db=PATH` for a read-only witness source. CPU prefix misses or
limits leave the original class and its budget intact for GPU search. Neither
an exhausted restricted search nor a timeout is a lower bound or closes an
obligation.

Final reconstruction first restores two fixed points, reversing the third move
and producing 16-symbol states with budget 8. Unclosed states branch:
restore two for a gain-two second move, or three for a gain-three second move.
This produces 18- or 19-symbol states with budget 9. Reverse the first
move by restoring one fixed point in the first branch, or restoring none and
reversing every gain-zero support in the second. Both endpoints are covered,
all gaps and supports are enumerated, and actual forward gains are replayed.
No qualifying-move restriction is imposed on the first inverse step.
Intermediate GPU searches explore their full budgets (8 or 9), without the
former depth-five cap. Their time allowance defaults to 0
(disabled), configurable with `-Dtd18.reconstructionGpuMillis=MS`.
Every depth-limited miss or timeout propagates through all applicable inverse
branches. Final budget-10 root searches use full depth 10 without a time cutoff
and must produce checked witnesses. Exploration limits never reduce the budget
used for diameter closures or the required inverse coverage. See [operations](OPERATIONS.md) for timing
semantics and the GPU sorting-tree restrictions.

All waves deduplicate valid-pair classes on disk. The latest sealed wave may
resume with `--phase=avoidance --resume-work=PATH`; its version, route, domain,
budget, record validity and exact count are checked. Incompatible checkpoints are
rejected. Resume is a computation convenience, not proof verification.
CPU sorting and GPU dispatch use separate worker pools with an append-only disk
handoff, so waiting for GPU results cannot stop the CPU scan. Progress reports
give per-wave coverage and backlog counts. Every handoff is drained before
sealing the next wave; interrupted handoffs are regenerated from the sealed
source rather than trusted as complete coverage.
Each inverse generation stage, persistence drain, inventory count and scan
reports progress every ten seconds. Known totals show processed/total, percent
and remaining work. During the first unique-class count, the exact total is
explicitly unknown until enumeration finishes; the counted classes and rate
remain visible. Writer names include their state-set directory.

## Verify complete coverage

```bash
tools/td18 verify --phase=all
```

The CPU checker independently regenerates all roots, endpoint classes and all
three inverse waves and replays the database at each actual budget. Every
missing endpoint discharge is propagated regardless of obstruction markers.
Any missing final root result fails verification. Ranges and partial phases
certify only their stated scope. The final full-scope report is required for
the conditional conclusion TD(18) = 10.

## Endpoint table publication

The `endpoint-table` command runs through JShell using all available processors.
It validates every emitter/checker endpoint report pair, requires the exact
complete annotation set, and writes the endpoint table and both table totals
atomically into the source paper. Zero-count annotated types remain explicit.
Missing or disagreeing reports stop publication. Rebuild the reading PDF with
`latexmk -pdf -interaction=nonstopmode -halt-on-error -outdir=docs docs/td18-reduction-count.tex`.
The table's final column counts classes outside elementary diameter closures,
before individual certificate lookup; it excludes subsequent inverse workload.
