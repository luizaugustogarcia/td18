# TD(18) extremal-class pipeline

This repository implements the computation

```text
       +-> E13
E12 ---+
       +-> H13(7) -> E14 -> E15 -> E16 -> E17 -> TD(18) <= 10
```

where `E_n` is the set of diametral extended-toric classes at order `n`.
Here `H_n(r)` denotes the classes in `S_n` at distance at least `r`.
Together with the known lower bound, a completed and verified run establishes
`TD(18) = 10`. No completed large run is included in this repository, so the
repository alone does not claim that result.

## Reduction

Let `del_x(pi)` denote the permutation obtained by deleting symbol `x` from
`pi` and then standardizing the remaining values in their original order.
For every symbol `x`,

```text
d(pi) <= d(del_x(pi)) + 1.
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
- direct `N`-child fixed-point expansion across all available CPUs;
- piecewise approximation pruning: the Bafna–Pevzner `1.5` guarantee below
  3-norm eight and the `1.375` guarantee from 3-norm eight onward;
- exact CUDA bounded search through domain size 19 and depth 10;
- RocksDB class, candidate, processed-key, and witness stores;
- resumable annotated-partition checkpoints;
- deterministic stage manifests with counts and SHA-256 checksums;
- witness replay and exact-depth verification.

The stages are:

1. `e12`: classify every fixed-point-free class on the 13-symbol cyclic
   domain and retain the depth-six failures with depth-seven witnesses.
2. `e13`: generate children of `E12`, require every removal to belong to
   `E12`, and retain the depth-seven failures with depth-eight witnesses.
3. `h13`: independently form the fixed-point part of `H13(7)` as
   `Expand(E12)` and classify every fixed-point-free class on the 14-symbol
   cyclic domain through depth six. A search through depth eight supplies an
   upper-bound witness for each retained fixed-point-free class.
4. `e14`: generate children of `H13(7)`, require every removal to remain in
   `H13(7)`, and retain the depth-seven failures with depth-eight witnesses.
5. `e15`: generate children of `E14`, apply the all-removals-in-`E14` filter,
   and retain the depth-eight failures with depth-nine witnesses.
6. `e16`: generate the fixed-point part as `Expand(E15)`, classify every
   fixed-point-free 17-symbol class through depth eight, and retain `E16`.
7. `e17`: generate children of `E16`, require every removal to belong to
   `E16`, and retain the depth-nine failures with depth-ten witnesses.
8. `e18`: generate children of `E17`, apply the all-removals filter, and
   require a verified witness of length at most ten for every survivor.

The propagated stages fuse raw insertion, the all-removals test,
canonicalization, global deduplication, and bounded search. They do not
materialize the much larger unfiltered child sets. GPU acceleration of child
generation and canonicalization is deliberately outside the current scope;
the CUDA code is used only by exact bounded sorting.

All propagated stages process bounded batches across the available CPUs.
Deletion checks use RocksDB `MultiGet` in rounds: each round constructs just
one deletion per surviving child, rejecting misses before computing any
further deletions. Persistent deduplication and restart checkpoints are
unchanged; a parent is marked complete only after all its children are classified.

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
  -Dexec.args="e12 --work=/data/td18 --gpu-devices=0,1,2,3 --gpu-slots=16 --gpu-budget-mib=850"

mvn -Pgpu -pl pipeline compile exec:java \
  -Dexec.args="all --work=/data/td18 --gpu-devices=0,1,2,3 --gpu-slots=16 --gpu-budget-mib=850"

mvn -Pgpu -pl pipeline compile exec:java \
  -Dexec.args="verify --work=/data/td18 --gpu-devices=0,1,2,3 --gpu-slots=16 --gpu-budget-mib=850"
```

Additional options control the GPU queue, statistics period, generator
worker count, lookup batch size, and RocksDB cache; run the main class with
`help` to list them. `--lookup-batch-size=500` is the default for every
propagated stage; `1` selects scalar deletion lookups.

`--rocksdb-cache-mib=256` sets a bounded native-memory block cache **per open
store**, separate from the Java heap. Membership and witness reads populate
the cache. Index and Bloom-filter blocks use its high-priority pool, with
level-zero metadata pinned. Sequential scans do not populate the cache.
Increase this budget to accommodate larger working sets, allowing for all
concurrently open stores. Bloom filters are written into new tables; opening
an existing database does not retrofit filters into its old tables or rewrite
the data. The cache policy applies immediately to existing tables.

For a read-only comparison of uncached scalar, cached scalar, and cached
batched lookups at any parent order:

```bash
mvn -q -pl pipeline compile dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt
java --class-path "pipeline/target/classes:$(<pipeline/target/runtime-classpath.txt)" \
  tools/LookupBenchmark.java /data/td18/e12/classes 13
```

The benchmark takes the cyclic domain size (ordinary order plus one), not a
hard-coded stage. Optional positional arguments control workers, sampled
parents, repetitions, batch size, cache MiB, and passes. It compares identical
query multisets with separate caches and verifies equal hit counts. Its
lookup throughput excludes canonicalization and is not an end-to-end stage rate.

The `e13` and `h13` commands are separate resumable stages. Run `e13` first
to obtain the small extremal set through removal-filtered propagation; run
`h13` when preparing the larger threshold set required by `e14`.

Each completed stage is immutable by convention. If interrupted before its
manifest is written, global generation resumes at annotated-partition
boundaries, while propagated stages resume from persistent parent and
candidate sets. Both routes safely deduplicate already persisted classes.

## Lineage

`../td` remains the mathematical source of truth. `../td16` is a historical
reference for the generator and CUDA search only; its TD(16)-specific proof
routes are not present here.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
