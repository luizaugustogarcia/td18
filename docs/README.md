# TD(18) proof documentation

[The source paper](td18-reduction-count.tex) is authoritative for the active
Eriksson-plus-third-move route.

| Document | Responsibility |
| --- | --- |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Required checked evidence for the proof. |
| [REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md) | Reference machine, timings, configuration, retained logs, and separate-machine verification. |
| [WORKFLOW.md](WORKFLOW.md) | Count, publish, emit, reconstruct and verify via JShell. |
| [OPERATIONS.md](OPERATIONS.md) | Explicit budgets, CPU/GPU configuration, persistence and resume. |
| [RTX5090.md](RTX5090.md) | Optional server launch profile and run storage. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Independent modules, generators and three inverse waves. |
| [KNOWLEDGE.md](KNOWLEDGE.md) | Pair conventions, encoding, symmetry and counting. |
| [td18-burnside-counting.md](td18-burnside-counting.md) | Burnside derivation and the complete endpoint census. |

Update the paper before changing the route, then align both implementations,
counts, tests, guides and completion artifacts. Generated databases and work sets remain
untracked; completed reference logs and settings in `logs/reference/` are committed.
A source/code disagreement must be identified explicitly.

[VALIDATION.md](VALIDATION.md) defines validation commands, artifact locations
and the independent verification record for the reference simulation.
