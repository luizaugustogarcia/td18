# Runtime operations

The default commands use the active route in
[the source paper](td18-reduction-count.tex).

## JShell, CPU and GPU configuration

`tools/td18 build` compiles both modules and prepares their dependency
classpaths. Every prepared Java entry point runs through JShell. CPU counts use
all JVM-visible processors, bounded generation queues and disjoint rank-placement
prefix tasks. `tools/td18 test` runs both required Maven CPU suites.
CUDA-dependent tests run only in a configured environment.

Set `TD18_JSHELL_OPTIONS` for execution-VM options, for example
`-R-Xmx3g` or `-R-Dtd18.twistedBraceletWorkers=auto`. The launcher passes
`-R-Dtd18.countModule=emitter|checker` to keep report ownership separate.
Use `tools/td18 build-gpu` for native CUDA libraries. GPU sorting is entered
only after applicable CPU prefixes and known-diameter checks fail.

GPU properties retain the shared machinery: `td18.gpuDevices`,
`td18.gpuSlots`, `td18.gpuBudgetMB`, `td18.gpuQueueCapacity`,
and `td18.gpuStatsPeriodSeconds`. CPU prefix properties are
`td18.cpuPrefixDepth`, `td18.cpuPrefixMaxTriples`, and
`td18.reconstructionCpuPrefixMillis`. Limits bound a heuristic; they do not
remove the original sorting obligation.

Reconstruction runs CPU searches and GPU dispatch in separate worker pools.
CPU workers scan the sealed source wave without waiting for GPU capacity;
only CPU misses enter an append-only disk queue. GPU workers drain that queue
concurrently and reconstruct parents only after an actual GPU miss. The CPU
prefix depth defaults to 5, with at most 8192 support checks and a 10 ms time
allowance per class. `td18.cpuPrefixDepth` overrides the depth; outside
reconstruction its default remains 3. The support and time properties retain
their configured values. A CPU miss sends the original class and full budget
to CUDA. CUDA returns as soon as a generated prefix reaches identity or the
known diameter of its fixed-point-contracted remainder fits the remaining
budget, including during first-move seeding.
`td18.reconstructionGpuWorkers` optionally controls the dispatch pool; its
default is twice the total GPU slots plus `td18.gpuQueueCapacity`, covering
active searches and the native dispatcher queue. These workers mostly wait
for GPU results and do not replace the CPU pool.

Progress reports identify each wave and show its total, cached results, CPU
closures, queued cases, GPU closures, GPU misses and remaining backlog.
A wave completes only after all CPU work and GPU results have joined and its
coverage counts agree. Increasing queue capacity does not increase GPU capacity.

## Budgets and persistence

| State | Symbols | Budget |
| --- | ---: | ---: |
| Direct/final root | 19 | 10 |
| Endpoint | 14 | 7 |
| After two moves | 16 | 8 |
| After one move, gain-one branch | 18 | 9 |
| After one move, gain-zero branch | 19 | 9 |

A domain alone no longer determines the obligation budget: 19-symbol states
can occur with budgets 9 and 10. Certificate persistence therefore
validates intrinsic results against the project maximum, and lookups replay them
against the explicit requested budget. A valid longer result is a miss for a
smaller budget. Malformed results are errors. Intrinsic witnesses are independent of the route and reusable after replay;
checkpoints must match the exact active route and format.

Certificate persistence uses `td18.certificateQueueCapacity` and
`td18.certificateFlushSeconds`. Inverse sets use
`td18.reconstructionBufferKeys` and batched reads controlled by
`td18.certificateLookupBatchSize`. Queues and in-memory key buffers are
bounded. Class keys and disk iteration are deterministic at persistence
boundaries. `certificates/`, `data/`, `logs/` and work databases are untracked.

Each handoff file has format `td-reconstruction-queue-v1`, a route/domain/budget
header, fixed-size records containing versioned canonical class keys and their
explicit budgets, and a final record count. An unfinished file keeps count -1.
Queue memory is bounded independently of its disk backlog; truncated records
and incomplete coverage are errors. Handoff files are temporary execution state,
never proof inputs or resume sources. After interruption, resume regenerates
the handoff from the latest complete sealed wave and replays stored certificates.

Each sealed-wave checkpoint has route ID `td18-eriksson-three-v1`, explicit
budgets and counts, and its own versioned file `eriksson-reconstruction-v1.txt`.
Resuming validates every record of the latest sealed set and its exact count.
The checker requires fresh work outside the certificate database and never trusts
or resumes emitter sets. Checksums, obstruction markers and sidecar manifests
cannot replace regenerated proof obligations.

## Entry points and options

`emit` accepts `--phase=roots|endpoints|avoidance|all`,
`--partitions=FIRST-LAST`, `--endpoints=FIRST-LAST`, `--paper=PATH`,
`--database=PATH`, `--work=PATH`, and `--source-db=PATH`.
`--resume-work=PATH` requires the complete avoidance scope without ranges or a
witness-source import. `verify` supports the same phase/range selection and
fresh `--work`, but never emitter checkpoint inputs. `inventory` performs only
structural checks. Source counts and full sorting verification remain separate.
