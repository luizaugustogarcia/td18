# Agent Notes

## Sources of truth

- This repository targets `TD(18)`. Never present the target equality as proved
  until every stage in `docs/RESEARCH_PLAN.md` has a checked artifact.
- Use `../td` as the mathematical source of truth. Its `AGENTS.md` terminology
  and scientific-framing rules apply to mathematical prose here.
- Use `../td16` as the implementation baseline. Preserve the separation
  between witness-producing search and independent certificate checking.
- If paper and code differ, identify the mismatch explicitly; do not silently
  choose one.

## Mathematical conventions

- Use **transposition** for the sorting move and **2-cycle** for the algebraic
  object.
- A cyclic-target pair is `(omega, beta)`, with target `rho = omega * beta`.
- `E_n` denotes diametral extended-toric classes at order `n` in implementation
  documentation. State explicitly when a claim concerns individual
  permutations rather than classes.
- Symbol deletion means deletion from both full cycles `beta` and `rho`,
  followed by order-preserving relabeling. Child insertion is its inverse:
  insert the new largest symbol into one gap of each full cycle.
- Fixed points of `omega` are first-class data in this project. Do not apply a
  fixed-point-free encoding or generator without an explicit precondition.

## Computational proof discipline

- Keep exhaustive generation, search, and verification boundaries explicit.
- A sorting witness proves an upper bound. Membership in `E_n` additionally
  needs exhaustive failure below the claimed distance.
- Every persisted set or certificate needs a versioned format, deterministic
  keys, counts/checksums, and a checker that rejects missing data.
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
