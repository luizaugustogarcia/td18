# Documentation map

[The source paper](td18-reduction-count.tex) is authoritative for the active
Eriksson-plus-third-move route.

| Document | Responsibility |
| --- | --- |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Required checked artifacts and proof completion. |
| [WORKFLOW.md](WORKFLOW.md) | Count, publish, emit, reconstruct and verify via JShell. |
| [OPERATIONS.md](OPERATIONS.md) | Explicit budgets, CPU/GPU configuration, persistence and resume. |
| [RTX5090.md](RTX5090.md) | RTX 5090 / AMD EPYC 7742 launchers and run storage. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Independent modules, generators and three inverse waves. |
| [KNOWLEDGE.md](KNOWLEDGE.md) | Pair conventions, encoding, symmetry and counting. |
| [td18-burnside-counting.md](td18-burnside-counting.md) | Burnside derivation and the complete endpoint census. |

Update the paper before changing the route, then align both implementations,
counts, tests, guides and completion artifacts. Generated results remain
untracked. A source/code disagreement must be identified explicitly.

[VALIDATION.md](VALIDATION.md) defines validation commands, artifact locations
and remaining obligations for the RTX 5090 / AMD EPYC 7742 run.
