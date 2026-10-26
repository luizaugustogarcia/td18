# TD(18) two-reduction computation

This repository implements the conditional two-reduction route specified in
[docs/td18-reduction-count.tex](docs/td18-reduction-count.tex). It retains the
complete inventory of 292 annotated types and generates, searches, and checks
their remaining cyclic-target class obligations.
At every recursion depth, a totally unoriented type receives no structural
reduction: a known diameter closes it when possible, and otherwise it becomes
a direct obligation.

**TD(18) = 10 is not proved here.** Every exit criterion in
[the research plan](docs/RESEARCH_PLAN.md) still needs its checked artifact.
The checker regenerates obligations and validates their witnesses or closure
arguments directly from the certificate database. Checksums and sidecar files
are not proof inputs. The exceptional oriented 9-cycle branch emits and checks
its 19-symbol extensions; its complete computational run remains pending.
The exceptional oriented 15-cycle branch separately covers 90 root classes
within ten transpositions and 7 intermediate classes within nine. Its
classification, generation counts, and 86 CPU prefix closures are checked.
Complete witnesses remain pending for 11 root classes; all seven intermediate
classes close by a regenerated prefix and a known smaller diameter.

The verification workflow is **emitter → certificate database → checker**.
The checker must independently regenerate every required obligation, reject
missing or unresolved obligations, and validate every witness or justified
closure against its move budget.

**Instruction for maintainers and agents:** Do not reintroduce mandatory
checksum validation, sidecar-manifest requirements, or
"file-integrity validation deferred" warnings. Verification must establish
completeness and correctness through regeneration and witness/closure checks.

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
