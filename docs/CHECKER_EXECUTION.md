# Checker component interactions

The checker regenerates TD(18) obligations and reads intrinsic results from the
certificate database. It performs no search. See the [execution map](EXECUTION.md)
and [emitter walkthrough](EMITTER_EXECUTION.md) for the connecting artifacts.

## 1. Independent coordinator

[CertificateChecker](../checker/src/main/java/br/unb/cic/td18/CertificateChecker.java)
dispatches to
[ErikssonCertificateChecker](../checker/src/main/java/br/unb/cic/td18/verification/ErikssonCertificateChecker.java).
It loads the paper inventory, opens the source database read-only and creates
fresh checker-owned scratch.

```mermaid
flowchart TB
    Main["ErikssonCertificateChecker.main"] --> Inventory["Validate 53 roots and 74 endpoints"]
    Inventory --> Store["CertificateStore: read only\nCheckerStateSet: fresh"]
    Store --> Roots["verifyRoot"]
    Roots --> Endpoints["verifyEndpoint"]
    Endpoints --> Avoid["verifyAvoidance\nThree inverse waves"]
    Avoid --> Final["Replay every final root"]
    Final --> Success["Complete-scope success marker"]
```

Structural-only and restricted executions validate only their requested scope.
Only an unfiltered `--phase=all` run reaches the complete TD(18) marker. Emitter
checkpoints, obstruction lists and work sets do not supply obligations.

## 2. Root verification

```mermaid
flowchart TB
    Row["One complete root row"] --> Gen["Checker ErikssonRootGenerator"]
    Gen --> Pair["Retained CyclicTargetPair"]
    Pair --> Clear{"Checker SupportClearing closes?"}
    Clear -->|yes| Elementary["Count clearing"]
    Clear -->|no| Batch["CertificateBatchVerifier\nBudget 10"]
    Batch --> Store["Canonical lookup and replay"]
    Store -->|valid within budget| Done["Verified class"]
    Store -->|missing or over budget| Fail["Stop verification"]
    Gen --> Compare["Require regenerated U, O and C = paper row"]
```

The checker regenerates every selected row, including zero-direct rows.
[ErikssonRootGenerator](../checker/src/main/java/br/unb/cic/td18/joint/ErikssonRootGenerator.java)
and [SupportClearing](../checker/src/main/java/br/unb/cic/td18/joint/SupportClearing.java)
are checker-owned implementations.

## 3. Endpoint verification

```mermaid
flowchart TB
    Type["Endpoint annotation"] --> Easy{"Elementary closure?"}
    Easy -->|yes| Closed["Annotation complete"]
    Easy -->|no| Burnside["Burnside representative count"]
    Burnside --> Generate["Independent representative stream"]
    Generate --> Batch["CertificateBatchVerifier\nBudget 7"]
    Batch -->|hit| Verified["Discharged"]
    Batch -->|miss or over budget| Residual[("Checker endpoint residual")]
    Generate --> Match["Require generated = Burnside"]
```

The residual callback uses the regenerated pair directly and never asks whether
an obstruction marker exists. Zero-realization non-elementary types still pass
count/generation agreement. The implementations are
[ErikssonInventory](../checker/src/main/java/br/unb/cic/td18/joint/ErikssonInventory.java),
[DirectTwistedBraceletGenerator](../checker/src/main/java/br/unb/cic/td18/bracelet/DirectTwistedBraceletGenerator.java)
and [TwistedBraceletBurnsideCounter](../checker/src/main/java/br/unb/cic/td18/bracelet/TwistedBraceletBurnsideCounter.java).

## 4. Batched lookup and replay

[CertificateBatchVerifier](../checker/src/main/java/br/unb/cic/td18/verification/CertificateBatchVerifier.java)
collects bounded batches and limits concurrent lookup pipelines. Closing it
processes the final partial batch.

```mermaid
sequenceDiagram
    participant G as Generator or state scan
    participant B as CertificateBatchVerifier
    participant S as CertificateStore
    participant D as Read-only RocksDB
    participant V as CertificateValidator
    participant M as Missing callback
    G->>B: accept(pair)
    B->>S: findAll(batch, requested budget)
    S->>S: Compute canonical keys
    S->>D: Position-preserving multiGet
    D-->>S: Values or misses
    loop Every hit
        S->>V: Decode and replay in canonical frame
        V-->>S: Required budget or error
    end
    S-->>B: Boolean per input position
    B->>M: Missing or valid over-budget pair
```

[CertificateStore](../checker/src/main/java/br/unb/cic/td18/certificate/CertificateStore.java)
requires the version-2 schema and `joint-selection-v3` marker. A stored key does
not establish that an input pair is canonical; the store compares normalized
images or reconstructs the canonical pair before replay. Malformed records and
invalid moves are errors. A valid result exceeding the requested budget is a
normal phase miss.

## 5. Replay boundary

[CertificateValidator](../checker/src/main/java/br/unb/cic/td18/certificate/CertificateValidator.java)
applies every stored transposition to fresh state.

```mermaid
flowchart TB
    Input["Canonical pair and SortingCertificate"] --> Moves["Replay moves; check applicability and target"]
    Moves --> Kind{"Result kind"}
    Kind -->|COMPLETE| Identity["Require identity remainder"]
    Kind -->|DIAMETER_CLOSURE| Contract["Contract actual fixed points"]
    Contract --> Known["Look up actual remaining diameter"]
    Identity --> Required["Required = move count"]
    Known --> Required2["Required = prefix + diameter"]
    Required --> Compare["Compare project and requested budgets"]
    Required2 --> Compare
```

The replay-derived requirement decides whether a result discharges the current
obligation. Neither a result label nor a recorded move count is trusted alone.

## 6. Independent inverse coverage

`verifyAvoidance()` builds fresh disk-backed sets from regenerated endpoint
residuals.

```mermaid
flowchart TB
    E[("endpoint-residuals\n14 symbols, budget 7")]
    E --> G2["Inverse gain 2, both endpoints"]
    G2 --> A[("after-two\n16 symbols, budget 8")]
    A --> Lookup8["Replay budget-8 results"]
    Lookup8 -->|miss| G23["Inverse gains 2 and 3"]
    G23 --> B[("after-one\n18 or 19 symbols, budget 9")]
    B --> Lookup9["Replay budget-9 results"]
    Lookup9 -->|miss| G10["Inverse gain 1 or 0"]
    G10 --> R[("roots\n19 symbols, budget 10")]
    R --> Final["Required budget-10 replay"]
    Final --> Report["Print exact wave counts"]
```

[ErikssonInverseReconstruction](../checker/src/main/java/br/unb/cic/td18/joint/ErikssonInverseReconstruction.java)
uses checker-owned permutation multiplication and sequential insertion. It
covers every support and gap, swaps endpoints explicitly and replays forward
gain. `requireStage()` checks each domain and budget. Every miss expands through
all applicable next branches; an empty successor still requires a full scan.

## 7. Checker state storage

[CheckerStateSet](../checker/src/main/java/br/unb/cic/td18/verification/CheckerStateSet.java)
wraps [CheckerStateStorage](../checker/src/main/java/br/unb/cic/td18/verification/CheckerStateStorage.java).
Canonical keys include the explicit budget. Bounded batches are partitioned
among deterministic writer shards, deduplicated, written without WAL and flushed
before sealing.

```mermaid
sequenceDiagram
    participant Phase as Checker phase
    participant Source as Sealed CheckerStateSet
    participant Scan as Concurrent scan
    participant Replay as Lookup or inverse callback
    participant Next as Successor set
    Phase->>Source: forEachConcurrently
    Source->>Scan: Validated state batches
    Scan->>Replay: SearchState(pair, budget)
    Replay->>Next: Miss or generated parent
    Scan-->>Phase: Exact count and joined callbacks
    Phase->>Next: seal()
    Next-->>Phase: Distinct inventory
```

Malformed keys, nonempty values, callbacks, iterator failures and count
disagreements abort verification. Fresh work prevents emitter scratch from
becoming a proof input.

## 8. Complete success boundary

| Phase | Required completed work |
| --- | --- |
| Root row | Independent generation, all count comparisons, clearing and budget-10 replay. |
| Endpoint type | Burnside count, generation, budget-7 classification and count equality. |
| After two | Every endpoint residual expanded and every class classified at budget 8. |
| After one | Every after-two miss expanded through both branches and classified at budget 9. |
| Final roots | Every after-one miss expanded through gain one or zero; every root replayed at budget 10. |
| Application | All work joined with no missing result; complete scope prints the success marker. |

The [reference execution](REFERENCE_SIMULATION.md) completed this boundary with
19 endpoint residuals, 25,544 after-two classes, 6,451,327 after-one classes,
1,349,910,890 final roots and zero missing result. Retained logs and settings are
catalogued in [logs/reference](../logs/reference/README.md).
