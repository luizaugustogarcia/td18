# Documentation map

Start with [the workflow](WORKFLOW.md) to choose the next computation or check.
Use [the operations guide](OPERATIONS.md) for exact Maven arguments, CUDA
configuration, and database behavior.

| Document | Responsibility |
| --- | --- |
| [td18-reduction-count.tex](td18-reduction-count.tex) | Source of truth for the conditional two-reduction route and all 292 Table 2 obligations. |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Required checked artifacts and exit criteria for stages 0–5. |
| [WORKFLOW.md](WORKFLOW.md) | Stage order, runnable tools, outputs, and the limits of each check. |
| [OPERATIONS.md](OPERATIONS.md) | Detailed commands, tuning, import, resume, and partial verification. |
| [KNOWLEDGE.md](KNOWLEDGE.md) | Mathematical vocabulary, source references, and implementation entry points. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Package responsibilities, checker independence, and persistence boundaries. |
| [td18-burnside-counting.md](td18-burnside-counting.md) | Derivation and small-domain validation of the exact R counter. |
| [two-cycle-residual-route.md](two-cycle-residual-route.md) | Active restriction of rows 267, 270, and 274 to 1,884 seeds, direct root lifting, independent coverage checks, and workload bounds. |
| [table2-provenance.html](table2-provenance.html) | Version 6 view of every selected type-level reduction path, restricted root-lifting obligations, exceptional CPU prefix closures, and remaining certificate obligations. |

The PDF and provenance graph are views of the route, not proof certificates.
The HTML template is maintained separately; regenerate the graph with
tools/td18 provenance. Generated computational artifacts stay outside Git.

When changing the route, update the source document first and independently
recheck its argument and inventory. When changing execution or tooling,
preserve the route and update the workflow and operations guides. Record any
code/document mismatch explicitly in the research plan.
