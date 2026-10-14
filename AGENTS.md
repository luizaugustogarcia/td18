# Agent Notes

## Repository priorities

- Do not use the GitHub CLI (`gh`); use `git` directly.
- Prioritize the completeness and correctness of the Java/CUDA pipeline under
  `pipeline/src/` and its checked computational artifacts.

## Sources of truth

- This repository targets `TD(18)`. Never present the target equality as proved
  until every stage in `docs/RESEARCH_PLAN.md` has a checked artifact.
- Use `../td` as the mathematical source of truth for the twisted-bracelet
  framework and the established `TD(16)` result. The rules below import and
  adapt the relevant conventions from its `AGENTS.md` to this project.
- Use `../td16` only as the historical implementation reference. Do not copy
  TD(16)-specific routing code into this repository.
- If documentation and code differ, identify the mismatch explicitly; do not
  silently choose one.

## Scientific framing

- Foreground the deletion reduction, class-level propagation, and the proof
  obligations for the `E_12`-to-order-18 pipeline.
- When describing the upstream twisted-bracelet framework, explain its
  fixed-content encoding of cyclic-target pairs, auxiliary standardization,
  twisted dihedral action, orbit correspondence, counting, and direct
  generation of one representative per realizable class.
- Treat `TD(16)=9` as a significant established application of that framework,
  never a "simple" or "mere" application. It does not certify `TD(18)`.
- Preserve the distinction between upstream results, the present reduction,
  and pending computational conclusions in documentation.

## Mathematical conventions

- Use **transposition**, not *adjacent-block transposition*, for the sorting
  move; reserve **2-cycle** for the algebraic object.
- Cycle parity means cycle-length parity. Write **odd-length cycle** and
  **even-length cycle**, never *odd cycle* or *even cycle*.
- Introduce mathematical objects with *let*, *set*, or *define*, not *put*.
- A cyclic-target pair is `(omega, beta)`, with target `rho = omega * beta`.
  Here `beta` is the current cycle, `omega = rho * beta^{-1}` is the algebraic
  permutation, and `rho` is the target cycle. For an ordinary SBT pair these
  specialize to the encodings of the input permutation, its algebraic
  permutation, and the identity target, respectively.
- `E_n` denotes diametral extended-toric classes at order `n` in implementation
  documentation. State explicitly when a claim concerns individual
  permutations rather than classes.
- Symbol deletion means deletion from both full cycles `beta` and `rho`,
  followed by order-preserving relabeling. Child insertion is its inverse:
  insert the new largest symbol into one gap of each full cycle.
- Fixed points of `omega` are first-class data in this project. Do not apply a
  fixed-point-free encoding or generator without an explicit precondition.
- A twisted bracelet is an orbit, not a canonical output word. The twisted
  reflection reverses word positions and inverts cyclic ranks on oriented
  beads; it is not ordinary word reversal. Motivate it through the encoded
  permutation and the extended-toric action.
- `CyclicTargetCandidate` is an unchecked realizability candidate. Convert it
  with `asCyclicTargetPair()` or `requireCyclicTargetPair()` before treating it
  as a valid pair. Use `TwistedBraceletKey` for candidate-orbit deduplication
  and `ExtendedToricClassKey` for valid-pair classes.

## Code and documentation

- Keep documentation and code mathematically consistent. Check corresponding
  definitions, formulas, bounds, algorithms, examples, and tests before
  changing either side; flag divergences rather than silently editing one side.
- Explain the method through mathematical objects, invariants, and proof ideas.
- Define every term and symbol before use; remove unused definitions and avoid
  parallel notation.
- Prove or cite every nontrivial claim.
- State certification results with explicit hypotheses. Claim a completed
  diameter result only when the required checked artifacts establish it.
- Keep worked examples verifiable and consistent with the code and tests.
- State assumptions and distinguish proved bounds from experimental evidence,
  estimates, and conditional results.
- Use direct prose and consistent terminology.

## Computational proof discipline

- Keep exhaustive generation, search, and verification boundaries explicit.
- A sorting witness proves an upper bound. Membership in `E_n` additionally
  needs exhaustive failure below the claimed distance.
- Every persisted set or certificate needs a versioned format, deterministic
  keys, counts/checksums, and verification that rejects missing data.
- Validate small orders and the known `E_15` corpus before starting the large
  `E_16` run.
- Generated databases, checkpoints, logs, and certificates must remain
  untracked.

## Engineering

- JDK 21 is the baseline.
- Prefer immutable value keys and deterministic iteration at persistence
  boundaries.
- Add regression tests for symmetry claims and inverse operations before using
  them as pruning rules.
- Run `mvn -pl pipeline -am test` for CPU-side work. Run CUDA-dependent tests
  only when the environment is configured for them.
