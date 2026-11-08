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
- Preserve the distinction between the shared framework, the joint-selection
  argument, and pending computational conclusions.
- If documentation and code differ, identify the mismatch explicitly; do not
  silently choose one.
- Explain fixed-content encoding, auxiliary standardization, the twisted
  dihedral action, orbit correspondence, counting, and direct generation when
  these objects are relevant.

## Mathematical conventions

- Use transposition for the sorting move; reserve 2-cycle for the algebraic
  object. Write odd-length cycle and even-length cycle for cycle parity.
- Introduce mathematical objects with let, set, or define, not put.
- A cyclic-target pair is (omega, beta), with target rho = omega * beta.
  Here beta is the current cycle and omega = rho * beta inverse is algebraic.
- Fixed points are first-class data. Contract them before using a
  fixed-point-free encoding or generator, and state that precondition.
- A twisted bracelet is an orbit, not a canonical word. Twisted reflection
  reverses positions and inverts oriented cyclic ranks; it is not ordinary
  word reversal.
- CyclicTargetCandidate is unchecked. Convert with asCyclicTargetPair() or
  requireCyclicTargetPair() before treating it as a valid pair.
- Use TwistedBraceletKey for candidate-orbit deduplication and
  ExtendedToricClassKey for valid-pair classes.

## Joint selection and coverage

- Keep all 53 paper root partitions and the independently generated 104
  endpoint annotations consistent with the implementation.
- First test all moves creating at least two fixed points. Otherwise inspect
  every qualifying gain-one first move and every gain-at-least-two second
  move. Include supports within even-length cycles and across cycles.
- At every depth, totally unoriented states receive no structural reduction.
  Use a known-diameter closure when it fits; otherwise retain the obligation.
- Use only the paper's explicit budget-valid support-clearing patterns.
- Endpoint sorting failures are final-phase obligations; never assume a
  predicted obstruction list or count.
- Reconstruct every compatible root by restoring two fixed points, reversing
  the second move, restoring one fixed point, and reversing the first move.
  Enumerate all gaps and supports and verify actual forward gains.
- Direct roots use budget ten; endpoints use eight; intermediate CPU screening
  uses nine. Every remaining reconstructed root needs a ten-move result.

## Code, documentation, and proof discipline

- Keep definitions, bounds, algorithms, tables, tests, and artifacts consistent.
  Define every term and symbol, and prove or cite every nontrivial claim.
- Distinguish established premises, completed counts, experimental checks,
  estimates, and conditional proof conclusions.
- Keep exhaustive generation, sorting, and verification boundaries explicit.
  A witness proves an upper bound only for its represented class.
- Every persisted set or certificate needs a versioned format, deterministic
  keys, counts, and verification that rejects missing data.
- Proof verification regenerates all obligations and replays witnesses from
  the database. Missing obstruction markers cannot omit work. Checksums and
  sidecar manifests are not required proof inputs.
- Validate small domains before large runs. Generated databases, checkpoints,
  logs, and certificates remain untracked.

## Engineering

- JDK 21 is the baseline. Prefer immutable keys and deterministic iteration
  at persistence boundaries.
- Keep the checker independent of the emitter artifact.
- Use disk-backed deduplication for large inverse waves and bounded queues
  for asynchronous persistence.
- Run mvn -pl emitter -am test and mvn -pl checker -am test for CPU-side work.
  Run CUDA-dependent tests only when the environment is configured for them.
