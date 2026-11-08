# TD(18) by joint selection of two reductions

This repository implements the conditional proof route in
[the source paper](docs/td18-reduction-count.tex). It jointly selects an
applicable first transposition and a favorable second transposition, directly
generates the classes left without such a prefix, and treats endpoint
sorting failures by final inverse reconstruction.

The completed count gives **248,082,611 direct root classes**:
214,474,050 totally unoriented classes and 33,608,561 oriented classes after
support clearing. The 19-cycle partition contributes 667 classes.
The 104 endpoint types include 52 elementary closures; the other 52 require
checked eight-move results or final reconstruction. Endpoint failure counts
are not assumed upfront. Reconstructed roots are additional final-phase work.

**TD(18) = 10 is not proved here.** Full sorting certificates and independent
end-to-end verification remain pending. A witness proves an upper bound only
for its represented class. The checker regenerates obligations, checks
witnesses and closures, and rejects missing data; checksums and sidecar
manifests are not proof inputs.

## Start here

- [Workflow](docs/WORKFLOW.md): counting, emission, final reconstruction, and checking.
- [Research plan](docs/RESEARCH_PLAN.md): checked artifacts required for completion.
- [Mathematical knowledge](docs/KNOWLEDGE.md): pairs, twisted bracelets, and reductions.
- [Operations](docs/OPERATIONS.md): commands, CPU workers, CUDA, and certificate storage.
- [Architecture](docs/ARCHITECTURE.md): generation, search, and independent verification.
- [Documentation map](docs/README.md): the source paper and remaining guides.

JDK 21, Maven, and Bash are the baseline. CPU validation does not require CUDA:

~~~bash
tools/td18 test
tools/td18 inventory
tools/td18 structure
~~~

Use tools/td18 help for all commands and tools/td18 --dry-run COMMAND to
inspect the Maven invocation. CUDA initializes only when a sorting search
needs it. Counting uses all available CPU cores by default.

| Path | Responsibility |
| --- | --- |
| emitter/ | Direct class generation, CPU/CUDA search, and certificate emission. |
| checker/ | Independent CPU regeneration, reconstruction, and witness replay. |
| docs/ | Source specification, mathematics, completion criteria, and operations. |
| tools/ | Workflow launcher and independent counting audits. |

The checker has no dependency on the emitter artifact. Generated databases,
checkpoints, logs, and certificates remain untracked. The sibling ../td
repository supplies the shared twisted-bracelet framework and established
mathematical conventions; the local paper specifies the TD(18) route.

Licensed under the [Apache License, Version 2.0](LICENSE).
