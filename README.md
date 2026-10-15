# TD(18) two-reduction computation

This repository studies the conditional two-reduction route in
[docs/td18-reduction-count.tex](docs/td18-reduction-count.tex). The route starts
from fixed-point-free cyclic-target types on 19 symbols, applies at most two
selected structural reductions, and leaves a complete inventory of 204
annotated types.

The repository does not currently prove TD(18) = 10. The local reduction
hypothesis and every generated class, bounded search, witness, count, and
verification artifact required by [the research plan](docs/RESEARCH_PLAN.md)
must be checked first.

## Method

A state consists of a contracted annotated cycle type T and the number s of
transpositions already used. Its remaining budget is 10 - s. A known diameter
closes a state when it fits that budget. Otherwise the current policy selects,
in order:

1. clearing an oriented 3-cycle;
2. a qualifying move on the smallest oriented odd-length cycle of length at
   least 5;
3. merging the two largest even-length cycles;
4. generation and sorting when no selected reduction applies.

Each reduction edge spends one transposition and lowers the 3-norm by one.
The recursion stops after two edges. Table 2 lists all 204 resulting annotated
types. Its `R` column counts every realizable extended-toric class; its `Q`
column retains only the class obligations not already discharged by the
checked TD(16) successor results. Thus no inventory row is removed: 102 rows
have `Q = 0`, two are partially settled, and 100 have `Q = R`. The remaining
total is 26,458,991,245 classes.

The cyclic-target and twisted-bracelet layer encodes a pair (omega, beta), with
target rho = omega * beta, as fixed-content words. Auxiliary standardization
and the twisted dihedral action identify extended-toric classes, and direct
generation emits one representative per realizable class. The twisted
reflection reverses word positions and inverts cyclic ranks on oriented beads;
it is not ordinary word reversal.

## Implemented components

The pipeline module contains:

- deterministic enumeration of the 204 annotated types;
- direct twisted-bracelet generation and exact Burnside counting;
- cyclic-target realizability checks and extended-toric canonicalization;
- Java/JNI/CUDA primitives for exact bounded sorting;
- certificate encoding and replay primitives;
- regression tests for the group action, counting, encoding, and reduction
  inventory.

End-to-end exhaustive generation, sorting, persistence, and independent
verification for the remaining `Q` classes remain to be implemented.

## Validate the type inventory

~~~bash
mvn -pl pipeline -am test

mvn -pl pipeline compile exec:java \
  -Dexec.mainClass=br.unb.cic.td18.ReductionTypeEnumerator \
  -Dexec.args=--validate
~~~

The validation reports 53 admissible partitions, 360 annotated roots, and 204
distinct obligations with depth distribution 8, 8, and 188.

## Count realizable classes

Count one realizable twisted-bracelet representative per extended-toric class
for every Table 2 entry:

~~~bash
mvn -pl pipeline compile exec:java
~~~

The counter reads [docs/td18-reduction-count.tex](docs/td18-reduction-count.tex),
uses all available CPU cores to apply Burnside's identity, validates each
recorded `R` value, and appends each completed count to the untracked
td18-bracelet-counts.log file. It does not run a sorting search or verify
sorting witnesses.

To count an inclusive subset of rows, use, for example:

~~~bash
mvn -pl pipeline compile exec:java -Dexec.args="--rows=50-204"
~~~

Optional --table and --log arguments select another input or output path. See
[the Burnside derivation](docs/td18-burnside-counting.md) for the counting
method.

## CUDA build

CPU-side compilation and tests do not require CUDA. On a configured CUDA
machine, build the exact-search native library with:

~~~bash
mvn -Pgpu package
~~~

The native search is a primitive used by future reduction-obligation
orchestration; building it does not by itself discharge any Table 2 row.

## Lineage

The two-reduction document is the source of truth for this repository's proof
route. The sibling ../td repository remains the source for the shared
twisted-bracelet framework and its mathematical conventions; it is not an
alternate TD(18) pipeline.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
