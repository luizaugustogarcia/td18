# Emitter component interactions

The emitter generates active TD(18) obligations, searches for intrinsic results
and persists replayable certificates. See the [execution map](EXECUTION.md) for
shared representations and the [checker walkthrough](CHECKER_EXECUTION.md) for
independent verification.

## 1. Coordinator

[WitnessEmitter](../emitter/src/main/java/br/unb/cic/td18/WitnessEmitter.java)
dispatches to
[ErikssonWitnessEmitter](../emitter/src/main/java/br/unb/cic/td18/emission/ErikssonWitnessEmitter.java).
The coordinator reads the paper inventory, creates fresh work, opens the
certificate writer and installs it as the search result sink.

```mermaid
flowchart TB
    Main["ErikssonWitnessEmitter.main"] --> Inventory["ErikssonInventory\n53 roots, 74 endpoints"]
    Inventory --> Writer[("AsyncCertificateWriter")]
    Writer --> Roots["emitRoot, budget 10"]
    Roots --> Endpoints["emitEndpoint, budget 7"]
    Endpoints --> Residual[("endpoint-residuals")]
    Residual --> Avoid["ErikssonAvoidance\nThree inverse waves"]
    Avoid --> Flush["Flush and report"]
```

The completion message describes only the requested emitter scope. Independent
verification remains required.

## 2. Direct roots

```mermaid
flowchart TB
    Row["One paper root row"] --> Zero{"Direct count zero?"}
    Zero -->|yes| Skip["Skip emitter work\nChecker still regenerates"]
    Zero -->|no| Gen["ErikssonRootGenerator.generate"]
    Gen --> Pair["Retained CyclicTargetPair"]
    Pair --> Clear{"SupportClearing closes?"}
    Clear -->|yes| Elementary["Count elementary clearing"]
    Clear -->|no| Search["WitnessSearch, budget 10"]
    Search -->|result| Persist["Replay and persist"]
    Search -->|residual| Fail["Stop row"]
    Gen --> Compare["Compare regenerated U, O and C"]
```

[ErikssonRootGenerator](../emitter/src/main/java/br/unb/cic/td18/joint/ErikssonRootGenerator.java)
uses normalized algebraic cycles, prefix placement and two-sided selection.
[SupportClearing](../emitter/src/main/java/br/unb/cic/td18/joint/SupportClearing.java)
implements only the paper's explicit patterns. Writer flush completes each row's
persistence boundary.

## 3. Endpoints

```mermaid
flowchart TB
    Type["Endpoint annotation"] --> Easy{"Elementary closure?"}
    Easy -->|yes| Closed["No sorting obligation"]
    Easy -->|no| Zero{"Published count zero?"}
    Zero -->|yes| Empty["No representatives"]
    Zero -->|no| Generate["DirectTwistedBraceletGenerator"]
    Generate --> Settle["settle(pair, budget 7)"]
    Settle -->|closed| Certificate["Persist checked result"]
    Settle -->|miss| Residual[("Endpoint residual")]
    Generate --> Coverage["Require generated = published count"]
```

[DirectTwistedBraceletGenerator](../emitter/src/main/java/br/unb/cic/td18/bracelet/DirectTwistedBraceletGenerator.java)
constructs fixed-content representatives. A configured
[ReadOnlyWitnessSource](../emitter/src/main/java/br/unb/cic/td18/certificate/ReadOnlyWitnessSource.java)
may supply a result, but it is replayed and copied through the ordinary TD(18)
writer. Avoidance-only emission derives misses from actual database lookup;
obstruction markers do not select work.

## 4. Witness search

[WitnessSearch](../emitter/src/main/java/br/unb/cic/td18/search/WitnessSearch.java)
owns lookup, CPU-prefix and CUDA boundaries. Direct unique generation starts a
fresh search; reconstruction first performs batched database lookup.

```mermaid
sequenceDiagram
    participant G as Generator or state scan
    participant W as WitnessSearch
    participant C as CpuPrefixSearch
    participant GPU as GPUSortingSearch
    participant V as CertificateValidator
    participant P as AsyncCertificateWriter
    G->>W: pair and explicit budget
    opt lookup-enabled
        W->>P: canonical lookup and replay
        P-->>W: hit or miss
    end
    W->>C: bounded prefix
    C-->>W: result or miss
    alt CPU miss
        W->>GPU: original pair and budget
        GPU-->>W: result or unresolved disposition
    end
    alt complete or closure
        W->>V: replay and derive budget
        V-->>P: enqueue intrinsic result
    else residual
        W-->>G: preserve obligation
    end
```

[CpuPrefixSearch](../emitter/src/main/java/br/unb/cic/td18/search/CpuPrefixSearch.java)
tries gain-at-least-two, gain-one and gain-zero supports within its limits. It
accepts only identity or a replayable diameter closure.
[GPUSortingSearch](../emitter/src/main/java/br/unb/cic/td18/base/GPUSortingSearch.java)
dispatches the restricted CUDA sorting tree. Exhaustion, caps, depth limits and
timeouts leave the input unresolved. Budget-10 final roots use full depth 10
without a time cutoff.

The native path is implemented by
[jni_sorter.cu](../emitter/src/native/jni/jni_sorter.cu),
[slot_manager.cu](../emitter/src/native/backtracking/slot_manager.cu) and
[sorting_search.cu](../emitter/src/native/backtracking/sorting_search.cu).

## 5. Three inverse waves

[ErikssonAvoidance](../emitter/src/main/java/br/unb/cic/td18/avoidance/ErikssonAvoidance.java)
seals each wave before using it as the next source.
[ErikssonInverseReconstruction](../emitter/src/main/java/br/unb/cic/td18/avoidance/ErikssonInverseReconstruction.java)
restores fixed symbols in every gap, covers both endpoints and replays the
forward gain.

```mermaid
flowchart TB
    E[("endpoint-residuals\n14 symbols, budget 7")]
    E --> G2["Inverse gain 2; restore two"]
    G2 --> A[("after-two\n16 symbols, budget 8")]
    A --> SearchA["Lookup, CPU, GPU"]
    SearchA -->|miss| G23["Inverse gains 2 and 3"]
    G23 --> B[("after-one\n18 or 19 symbols, budget 9")]
    B --> SearchB["Lookup, CPU, GPU"]
    SearchB -->|miss| G10["Inverse gain 1 or 0"]
    G10 --> R[("roots\n19 symbols, budget 10")]
    R --> Final["Mandatory lookup and full search"]
    Final -->|miss| Error["Fail completion"]
```

## 6. CPU, GPU and inverse pools

[ReconstructionPipeline](../emitter/src/main/java/br/unb/cic/td18/avoidance/ReconstructionPipeline.java)
keeps source scanning, GPU dispatch and inverse generation independent.

```mermaid
sequenceDiagram
    participant S as Sealed DiskStateSet
    participant L as Batched lookup
    participant C as CPU-prefix workers
    participant Q as DiskStateQueue
    participant G as GPU workers
    participant I as Inverse workers
    participant N as Successor set
    S->>L: Source batch
    L-->>C: Lookup misses
    C-->>Q: CPU misses only
    Q-->>G: Persistent handoff
    G-->>I: GPU misses only
    I->>N: Generate all parents
    N-->>S: Seal after writers drain
```

Coverage equalities account for every source, GPU input and inverse input. A
failed worker aborts both disk queues and prevents sealing.

## 7. Persistence and root prefetch

[DiskStateSet](../emitter/src/main/java/br/unb/cic/td18/avoidance/DiskStateSet.java)
and [StateSetWriter](../emitter/src/main/java/br/unb/cic/td18/avoidance/StateSetWriter.java)
use bounded producer batches, deterministic shards, sorted SST files and
WAL-free ingestion. Duplicate occurrences become one key.

During final inverse generation,
[RootPrefetch](../emitter/src/main/java/br/unb/cic/td18/avoidance/RootPrefetch.java)
performs batched lookup, sends misses through parallel CPU-prefix workers and
sends only CPU misses to time-sliced GPU search. Queue pressure may drop
advisory work because the sealed root set is scanned exhaustively afterwards.

## 8. Completion boundary

[ErikssonCheckpoint](../emitter/src/main/java/br/unb/cic/td18/avoidance/ErikssonCheckpoint.java)
records sealed stages with the route, budgets and exact counts. Resume validates
the complete source during mandatory processing. Emitter success requires all
generation, search and persistence work to join, every count comparison to pass,
every final root to close within budget 10 and the unresolved count to be zero.
The independent checker then verifies the resulting database.
