# A computer-assisted proof that TD(18) = 10

This repository contains the mathematical argument, certificate emitter, and
independent checker for a computer-assisted proof that TD(18) = 10.
The proof searches both the current and target cycles: two transpositions
produce three fixed symbols, then a third produces at least two more. Gain-zero
first moves are included. The original target need not be held fixed throughout
this triangle-inequality argument. The proof specification is
[the source paper](docs/td18-reduction-count.tex).

The source inventory records **2 direct root classes** after support clearing.
Regenerate and independently audit all counts for the current run.

The endpoint cover contains all 74 even fixed-point-free annotated types on 14 symbols, with budget 7.
The smaller established diameters, including the separately certified TD(16) = 9, remain premises.

A counting result is separate from a sorting certificate. Every remaining direct
root needs a checked result within 10 moves. Every non-elementary endpoint
needs a result within 7 moves or complete inverse coverage. Actual endpoint
misses generate three inverse waves, including both `(1,2,2)` and `(0,3,2)`
branches on both endpoints. Missing obstruction markers cannot suppress work.
A final reconstructed root always needs budget 10.

The proof combines exhaustive checked upper-bound coverage with the established
reverse-permutation lower bound of 10. A full independent checker run has
already succeeded. The retained [reference simulation](docs/REFERENCE_SIMULATION.md)
reproduces that result on this local computer; its database will also be verified
on a separate machine, whose finished logs will be added to the project.
[The evidence checklist](docs/RESEARCH_PLAN.md) specifies every required artifact.
The checker regenerates all obligations and replays intrinsic witnesses; emitter
checkpoints and sidecar manifests are not proof inputs.

JDK 21, Maven, Bash and JShell are the baseline:

```bash
tools/td18 build
tools/td18 test
tools/td18 inventory
tools/td18 count-roots
tools/td18 audit-roots
tools/td18 count-endpoints
tools/td18 audit-endpoints
bash -ic './tools/run-local-emitter.sh --build'
bash -ic './tools/run-local-emitter.sh'
bash -ic './tools/run-local-checker.sh --build'
bash -ic './tools/run-local-checker.sh'
```

The reference machine is an **Intel Core Ultra 9 185H / RTX 4070 Laptop GPU**
under WSL2: 22 CPU workers, a 16 GiB Java heap, and 8 GPU slots with 768 MiB
of queue memory each. [The reference record](docs/REFERENCE_SIMULATION.md)
contains the configuration, start/finish times, durations, and log locations.
The local launchers use those memory and queue limits with all allocated CPU
cores. They build SM 89 native code and record each new run separately from
the retained historical emission.

Java entry points run through JShell, using all JVM-visible CPUs by default.
The generic `tools/td18 build-gpu` retains the CMake default SM 120 target;
the local emitter launcher selects SM 89 explicitly. CPU counts,
checks and successful CPU prefixes do not initialize CUDA. Generated databases,
checkpoints and count reports remain untracked. Completed reference logs and
settings in `logs/reference/` are committed; other run logs remain untracked. The checker has no emitter artifact dependency. See [workflow](docs/WORKFLOW.md),
[operations](docs/OPERATIONS.md), [architecture](docs/ARCHITECTURE.md), and
[mathematical conventions](docs/KNOWLEDGE.md), and [validation](docs/VALIDATION.md).
