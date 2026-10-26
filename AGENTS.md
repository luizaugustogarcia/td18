# Agent Notes

## Repository priorities

- Do not use the GitHub CLI (gh); use git directly.
- Treat docs/td18-reduction-count.tex as the source of truth for the TD(18)
  proof route in this repository.
- Prioritize completeness and correctness of the Java/CUDA implementation and
  its checked computational artifacts.
- Never present TD(18) = 10 as proved until every exit criterion in
  docs/RESEARCH_PLAN.md has a checked artifact.

## Mathematical framework

- Use ../td only as the source for the shared twisted-bracelet framework and
  established mathematical conventions.
- Preserve the distinction between the shared framework, the present
  two-reduction argument, and pending computational conclusions.
- If documentation and code differ, identify the mismatch explicitly; do not
  silently choose one.
- Explain fixed-content encoding of cyclic-target pairs, auxiliary
  standardization, the twisted dihedral action, orbit correspondence,
  counting, and direct generation of one representative per realizable class
  when those objects are relevant.

## Mathematical conventions

- Use transposition for the sorting move; reserve 2-cycle for the algebraic
  object.
- Write odd-length cycle and even-length cycle when discussing cycle parity.
- Introduce mathematical objects with let, set, or define, not put.
- A cyclic-target pair is (omega, beta), with target rho = omega * beta.
  Here beta is the current cycle, omega = rho * beta^{-1} is the algebraic
  permutation, and rho is the target cycle.
- Fixed points of omega are first-class data. Do not apply a fixed-point-free
  encoding or generator without an explicit precondition.
- A twisted bracelet is an orbit, not a canonical output word. Twisted
  reflection reverses word positions and inverts cyclic ranks on oriented
  beads; it is not ordinary word reversal.
- CyclicTargetCandidate is unchecked. Convert it with asCyclicTargetPair() or
  requireCyclicTargetPair() before treating it as a valid pair.
- Use TwistedBraceletKey for candidate-orbit deduplication and
  ExtendedToricClassKey for valid-pair classes.

## Code and documentation

- Keep definitions, formulas, bounds, algorithms, examples, tables, and tests
  mathematically consistent.
- Define every term and symbol before use; remove unused definitions and avoid
  parallel notation.
- Prove or cite every nontrivial claim.
- State assumptions and distinguish proved bounds from experimental evidence,
  estimates, and conditional results.
- Keep Table 2 as the complete 292-type obligation inventory unless the
  two-reduction argument itself is changed and independently rechecked.
  The argument includes the cited support-clearing alternatives when their
  global budget fits. It preserves rows 1–204 and appends 88 early obligations
  (51 roots and 37 one-move states), independently checked by the work-list
  enumerator. The exceptional oriented 15-cycle is handled through its
  restricted companion interleavings, separately from the whole-type inventory.
  Regenerate its 86 checked CPU prefix closures; the remaining 11 root
  classes require complete ten-transposition witnesses.
- Use direct prose and consistent terminology.
- At every recursion depth, a totally unoriented annotated type receives no
  structural reduction. Apply a known-diameter closure when it fits; otherwise
  retain the type as a direct obligation. The even-pair fallback requires at
  least one oriented cycle somewhere in the type, although the two selected
  even-length cycles may both be unoriented.

## Computational proof discipline

- Keep exhaustive generation, sorting, and verification boundaries explicit.
- A sorting witness proves an upper bound only for the represented class.
- Every persisted set or certificate needs a versioned format, deterministic
  keys, counts, and verification that rejects missing data. Proof verification
  regenerates all obligations and checks witnesses directly from the certificate
  database; checksums and sidecar manifests are not required proof inputs.
- Validate small domains before large runs.
- Generated databases, checkpoints, logs, and certificates must remain
  untracked.

## Engineering

- JDK 21 is the baseline.
- Prefer immutable value keys and deterministic iteration at persistence
  boundaries.
- Run mvn -pl emitter -am test for CPU-side work. Run CUDA-dependent tests
  only when the environment is configured for them.
