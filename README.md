# TD(18) by Eriksson reduction and a third transposition

The active route searches both the current and target cycles: two transpositions
produce three fixed symbols, then a third produces at least two more. Gain-zero
first moves are included. The original target need not be held fixed throughout
this triangle-inequality argument. The proof specification is
[the source paper](docs/td18-reduction-count.tex).

The emitter count is complete: **2 direct root classes**, after support clearing. Independent checker regeneration matches every partition.

The endpoint cover contains all 74 even fixed-point-free annotated types on 14 symbols, with budget 7.
The smaller established diameters, including the separately certified TD(16) = 9, remain premises.

A counting result is separate from a sorting certificate. Every remaining direct
root needs a checked result within 10 moves. Every non-elementary endpoint
needs a result within 7 moves or complete inverse coverage. Actual endpoint
misses generate three inverse waves, including both `(1,2,2)` and `(0,3,2)`
branches on both endpoints. Missing obstruction markers cannot suppress work.
A final reconstructed root always needs budget 10.

The active route does not establish TD(18) = 10 until the artifacts in
[the research plan](docs/RESEARCH_PLAN.md) are complete. The checker regenerates
all obligations and replays intrinsic witnesses; emitter checkpoints and sidecar
manifests are not proof inputs.

JDK 21, Maven, Bash and JShell are the baseline:

```bash
tools/td18 build
tools/td18 test
tools/td18 inventory
tools/td18 count-roots
tools/td18 audit-roots
tools/td18 count-endpoints
tools/td18 audit-endpoints
tools/td18 emit --phase=all
tools/td18 verify --phase=all
```

Java entry points run through JShell, using all JVM-visible CPUs by default.
Build CUDA with `tools/td18 build-gpu` before a GPU sorting run. CPU counts,
checks and successful CPU prefixes do not initialize CUDA. Generated databases,
logs, checkpoints and count reports remain untracked. The checker has no emitter
artifact dependency. See [workflow](docs/WORKFLOW.md),
[operations](docs/OPERATIONS.md), [architecture](docs/ARCHITECTURE.md), and
[mathematical conventions](docs/KNOWLEDGE.md), and [validation](docs/VALIDATION.md).
