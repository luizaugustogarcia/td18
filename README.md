# TD(18) extremal-class pipeline

This repository implements the computation

```text
E15 -> E16 -> E17 -> TD(18) <= 10,
```

where `E_n` is the set of diametral extended-toric classes at order `n`.
Together with the known lower bound, a completed and verified run establishes
`TD(18) = 10`. No completed large run is included in this repository, so the
repository alone does not claim that result.

## Reduction

For every symbol `x`,

```text
d(pi) <= d(pi - x) + 1.
```

Thus a class can exceed `TD(n - 1)` only if all of its circular removals lie
in `E_(n-1)`. A cyclic-target representative on `N` symbols has `N^2` raw
children: insert the new largest symbol independently into a gap of the
current cycle and a gap of the target cycle, then canonicalize and deduplicate
at order `N + 1`.

## Implemented stages

The single `pipeline` module contains only code used by this computation:

- cyclic-target algebra and extended-toric canonicalization;
- direct twisted-bracelet generation of fixed-point-free classes;
- circular deletion and `N^2` child insertion;
- the 1.375 upper-bound pruning rule;
- exact CUDA bounded search through domain size 19 and depth 10;
- RocksDB class, candidate, processed-key, and witness stores;
- resumable annotated-partition checkpoints;
- deterministic stage manifests with counts and SHA-256 checksums;
- witness replay and exact-depth verification.

The stages are:

1. `e15`: enumerate the 16-symbol cyclic domain, reject everything sortable
   within eight moves, and retain classes with a nine-move witness.
2. `e16`: generate the fixed-point part as `Expand(E15)`, classify every
   fixed-point-free 17-symbol class through depth eight, and retain `E16`.
3. `e17`: generate children of `E16`, require every removal to belong to
   `E16`, and retain the depth-nine failures with depth-ten witnesses.
4. `e18`: generate children of `E17`, apply the all-removals filter, and
   require a verified witness of length at most ten for every survivor.

See [docs/RESEARCH_PLAN.md](docs/RESEARCH_PLAN.md) for the proof obligations
and validation corpus.

The working paper is in [docs/paper/main.tex](docs/paper/main.tex). It states
the deletion reduction and conditional certification theorem without claiming
that the pending large computation has completed.

## Build and test

CPU-side compilation and tests do not require CUDA:

```bash
mvn test
```

Build the native exact-search library on a CUDA machine with CMake 3.24+:

```bash
mvn -Pgpu package
```

## Run

Run one stage, all stages, or verification:

```bash
mvn -Pgpu -pl pipeline compile exec:java \
  -Dexec.args="e15 --work=/data/td18 --gpu-devices=0,1,2,3 --gpu-slots=16 --gpu-budget-mib=850"

mvn -Pgpu -pl pipeline compile exec:java \
  -Dexec.args="all --work=/data/td18 --gpu-devices=0,1,2,3 --gpu-slots=16 --gpu-budget-mib=850"

mvn -Pgpu -pl pipeline compile exec:java \
  -Dexec.args="verify --work=/data/td18 --gpu-devices=0,1,2,3 --gpu-slots=16 --gpu-budget-mib=850"
```

Additional options control the GPU queue, statistics period, and generator
worker count; run the main class with `help` to list them.

Each completed stage is immutable by convention. If interrupted before its
manifest is written, rerunning it resumes at annotated-partition boundaries
and safely deduplicates already persisted classes.

## Lineage

`../td` remains the mathematical source of truth. `../td16` is a historical
reference for the generator and CUDA search only; its TD(16)-specific proof
routes are not present here.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
