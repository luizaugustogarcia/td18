# TD(18) two-reduction computation

This repository implements the conditional two-reduction route specified in
[docs/td18-reduction-count.tex](docs/td18-reduction-count.tex). It retains the
complete inventory of 204 annotated types and generates, searches, and checks
their remaining cyclic-target class obligations.

**TD(18) = 10 is not proved here.** Every exit criterion in
[the research plan](docs/RESEARCH_PLAN.md) still needs its checked artifact.
The development checker currently verifies selected obligations without
enforcing sidecar integrity/completion records.

## Start here

- [Proof/computation workflow](docs/WORKFLOW.md): stage order, commands, outputs,
  and what each check establishes.
- [Documentation map](docs/README.md): source specification, research plan,
  mathematical knowledge, and operations.
- [Mathematical knowledge](docs/KNOWLEDGE.md): encoding, standardization,
  twisted dihedral action, orbit correspondence, counting, and generation.
- [Architecture](docs/ARCHITECTURE.md): code responsibilities and verification
  boundaries.
- [Operations](docs/OPERATIONS.md): CUDA tuning, database formats, row ranges,
  witness import, and resume commands.

## Run the CPU checks

JDK 21, Maven, and Bash are the baseline. From the repository root:

~~~bash
tools/td18 test
tools/td18 inventory
tools/td18 structure
~~~

Use tools/td18 help for counting, emission, avoidance, survivor certificates,
and CPU verification. Use tools/td18 --dry-run COMMAND to inspect a command.
CUDA-dependent operations explicitly select the gpu profile.

## Repository layout

| Path | Responsibility |
| --- | --- |
| emitter/ | Class generation, CPU/CUDA search, witness writing, and avoidance reconstruction. |
| checker/ | CPU regeneration and replay, with independent inventory and reconstruction paths. |
| docs/ | Route specification, completion criteria, mathematical explanations, and run guides. |
| tools/ | A command launcher for the proof/computation workflow. |

The checker has no dependency on the emitter artifact. Generated databases,
checkpoints, logs, and certificates remain untracked. A sorting witness proves
an upper bound only for its represented class.

The sibling ../td repository supplies the shared twisted-bracelet framework
and conventions. The local source document governs this TD(18) route.

Licensed under the [Apache License, Version 2.0](LICENSE).
