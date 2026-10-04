# TD(18) extremal-class computation

This repository is the working implementation for the remaining transposition
diameter problem at order 18. The target is to prove

```text
TD(18) = 10.
```

The lower bound 10 is already known. The computational task is therefore to
certify that every permutation in `S_18` can be sorted with at most ten
transpositions. This repository does **not** yet claim that result.

## Strategy

For a permutation `pi` and a symbol `x`, deleting `x` gives the elementary
bound

```text
d(pi) <= d(pi - x) + 1.
```

Consequently, a permutation whose distance is one more than `TD(n - 1)` can
exist only when every symbol deletion is diametral at order `n - 1`. We use
`E_n` for the set of diametral extended-toric classes at order `n`.

The intended computation is:

1. Compute and validate `E_15`.
2. Compute the complete `E_16`. Its fixed-point part is `Expand(E_15)`; its
   fixed-point-free part requires a new exhaustive depth-eight classification.
3. Generate the order-17 classes whose every circular removal belongs to
   `E_16`, then search the survivors through depth nine to obtain `E_17`.
4. Generate the analogous order-18 candidates from `E_17` and give every
   survivor a sorting sequence of length at most ten.

All generation is performed on extended-toric classes. For a representative
on an `N`-symbol cyclic domain, its `N^2` insertions (one gap in the current
cycle and one in the target cycle) cover all child classes; canonicalization
and deduplication happen only after insertion.

The detailed proof obligations, validation corpus, and stage gates are in
[docs/RESEARCH_PLAN.md](docs/RESEARCH_PLAN.md).

## Lineage

- `../td` is the mathematical source of truth: the twisted-bracelet paper,
  definitions, proof structure, and references.
- `../td16` is the computational baseline: direct class generation,
  canonicalization, exact CPU/GPU search, and certificate replay.

Only the tracked source from `td16` was used to bootstrap this repository.
The large certificate and reference-data directories were not copied.

## Modules

- `pipeline` contains the new extremal-class operations and staged TD(18)
  computation.
- `emitter` is the inherited GPU search and witness-production baseline.
- `checker` is the inherited independent CPU verification baseline.

At this initial milestone, `pipeline` implements and tests circular deletion,
the `N^2` insertion construction, fixed-point expansion, and class-level
symmetry invariants.

## Build

The new CPU-side milestone and all of its dependencies can be tested without
CUDA:

```bash
mvn -pl pipeline -am test
```

The full inherited build includes the CUDA emitter and retains the TD(16)
prerequisites documented in the repository history.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
