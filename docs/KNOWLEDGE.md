# Mathematical knowledge and references

This is a reading guide to existing definitions and results. The shared
twisted-bracelet framework comes from
[the sibling td paper](../../td/docs/paper/main.tex), Section
sec:twisted-bracelets. The present two-reduction policy and Table 2 come from
[the TD(18) source document](td18-reduction-count.tex). Pending computational
conclusions are governed by [the research plan](RESEARCH_PLAN.md).

## From a pair to a class

Let N = n + 1 be the cyclic-domain size for ordinary order n. A cyclic-target
pair is (omega, beta): beta is the current N-cycle, rho = omega * beta is the
target N-cycle, and omega = rho * beta inverse is the algebraic permutation.
The pair condition includes evenness of omega and target realizability.
CyclicTargetCandidate is unchecked; use asCyclicTargetPair() or
requireCyclicTargetPair() before treating it as a valid pair.

An annotated type records each algebraic cycle’s length and its orientation
relative to beta. For the fixed-point-free framework, all parts have length
at least two. A color identifies a cycle: an unoriented cycle contributes
repeated unranked beads, and an oriented cycle contributes each cyclic rank
once. Positions follow beta; colors identify supports and oriented ranks
identify cyclic order. Ranked orders that decode to the unoriented order are
excluded. This is the fixed-content encoding of the prescribed annotated type
(upstream Proposition prop:fixed-content-encoding).

Auxiliary symmetries rename equal annotated colors and shift oriented rank
origins without changing the decoded permutation. Auxiliary standardization
names colors by first occurrence and sets an oriented color’s first rank to
zero. StandardizedEncodingWord implements that choice. Internally, rank zero
means unranked and mathematical ranks are stored with an offset of one.

Twisted reflection reflects word positions and inverts cyclic ranks on
oriented beads. Rotation and this reflection form the twisted dihedral action;
ordinary word reversal is insufficient (upstream Propositions
prop:twisted-dihedral-action and prop:auxiliary-dihedral-action).

A twisted bracelet is an orbit, not its chosen canonical output word.
Decoder equivariance and realizability invariance identify realizable
bracelets with extended-toric classes of valid cyclic-target pairs (upstream
Proposition prop:decoder-equivariance, Corollary cor:realizability-invariance,
and Theorem thm:bracelet-class-correspondence). Use TwistedBraceletKey to
deduplicate candidate orbits and ExtendedToricClassKey for valid-pair classes.

Fixed points of omega are first-class data. The general model represents them
as singleton colors, including fixed points omitted from a multicycle writing.
The fixed-point-free generator and counter require their explicit precondition;
the two-reduction route contracts fixed points before generating terminal
types. The upstream fixed-point-free theorems do not by themselves assert a
generator for arbitrary singleton content.

## Count and generate separately

Burnside counting averages symmetry-fixed realizable objects to obtain the
number of classes (upstream Corollary cor:twisted-bracelet-burnside).
[The local counting derivation](td18-burnside-counting.md) describes the exact
identity, rotation, and reflection computations used for R. Small-domain tests
compare these counts with direct orbit generation.

DirectTwistedBraceletGenerator emits one representative from each realizable
class of its fixed-point-free annotated type. The framework’s correctness
statement is Theorem thm:twisted-generation-correctness. Exhaustive generation
must agree with the required Q count independently of sorting outcomes.
Counting alone gives no sorting witness.

## The TD(18) obligations

A qualifying 2-move is one transposition that increases the number of
odd-length algebraic cycles by two and creates a fixed point (upstream
Subsection interaction). It is not a sequence of two transpositions. With
beta = (0 1 ... 18), the two exceptional oriented 19-cycle classes in the
source document are represented by omega = beta to the power 10 and omega =
beta to the power 16. Their targets are beta to the powers 11 and 17,
respectively. Each power has cycle length 19/gcd(exponent, 19) = 19; the
algebraic 19-cycles are even, so both pairs are realizable.
Direct regression checks enumerate all 969 applicable
transpositions for each: the former has no 2-moves, and the latter has 57,
none qualifying. The DFS classification and the complete sorting certificates
are separate obligations. Both classes admit a complete ten-transposition
clearing sequence, explicitly given below and replayed by the independent
checker regression tests. Their inclusion and verification in the finished
proof database remains a separate requirement.

### Explicit clearing of the two oriented 19-cycle exceptions

Let beta = (0 1 ... 18). Each triple below denotes one transposition by its
three cut symbols, in the cyclic order of the current beta. Apply the triples
from left to right using omega' = omega * tau inverse and beta' = tau * beta.
This preserves rho = omega * beta. A 0-move preserves the number of odd-length
algebraic cycles; a 2-move increases that number by two.

For omega = beta to the power 10, a clearing sequence is:

~~~text
(0,1,9)   (2,9,11)  (9,8,18)  (8,12,17) (13,17,3)
(17,16,7) (16,4,6)  (5,6,14)  (1,6,10)  (6,10,15)
~~~

For omega = beta to the power 16, a clearing sequence is:

~~~text
(0,1,3)   (1,2,4)   (2,3,5)   (3,10,12) (11,8,14)
(12,7,15) (14,5,17) (10,16,13) (9,6,3)   (15,4,18)
~~~

Both sequences start with a 0-move and then use nine 2-moves. Counting fixed
points as odd-length cycles, the odd-length-cycle counts, including the
initial state, are 1, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19. At the final state,
omega is the identity and beta equals rho.

The [independent replay test](../checker/src/test/java/br/unb/cic/td18/verification/OrientedNineteenCycleClassificationTest.java)
checks applicability, target preservation, complete clearing within ten
transpositions, and the odd-length-cycle counts. The emitter also has
[CPU import and reuse tests](../emitter/src/test/java/br/unb/cic/td18/emission/OrientedNineteenCycleEmitterTest.java)
with ten-move witnesses. These witnesses prove an upper bound of ten for the
two represented classes. They do not complete the other TD(18) obligations.

### Two-reduction states and terminal obligations

Let a state be (T, s), where T is the contracted annotated type and s is the
number of transpositions already used. Its remaining budget is 10 - s. The
source document specifies known-diameter closure and the priority of the
three reductions, with a depth cap of two and fresh child annotations.
Table 2 remains the complete inventory of 204 terminal types. Its R and Q
columns serve different purposes; [the workflow](WORKFLOW.md) explains how
each becomes a computational obligation.

A short qualifying CPU prefix creates fixed points and closes the remainder
using an independently known diameter within the global budget. It is not a
complete sorting witness and is regenerated by the checker. A persisted
sorting certificate is a complete replayable sequence of transpositions that
proves an upper bound only for its represented class. A search miss is a
residual obligation; avoidance reconstructs the reachable predecessor paths
and requires each remaining full-domain root to close within ten moves.

## Find the implementation

Both modules use the br.unb.cic.td18 package prefix; paths below are relative
to each module’s src/main/java/br/unb/cic/td18 directory.

| Concept | Entry point |
| --- | --- |
| Pair validity and class identity | model/CyclicTargetCandidate.java, model/CyclicTargetPair.java, model/ExtendedToricClassKey.java |
| Encoding and auxiliary standardization | model/EncodingWord.java, model/StandardizedEncodingWord.java |
| Candidate orbit identity | model/TwistedBraceletKey.java |
| Direct generation and Burnside count | bracelet/DirectTwistedBraceletGenerator.java, bracelet/TwistedBraceletBurnsideCounter.java |
| Source-table access | table/Table2.java, table/TableEntry.java |
| Two-reduction policy | reduction/ReductionTypeEnumerator.java |
| Independent inventory and Q check | checker’s verification/IndependentTypeEnumerator.java and verification/PaperDischargeLemma.java |
| CPU prefixes and emitted searches | search/CommonOperations.java; emitter’s search/WitnessSearch.java |
| Witness encoding and replay | certificate/CertificateCodec.java, certificate/CertificateValidator.java |
| Predecessor reconstruction | avoidance/ObstructionAvoidance.java, independently implemented in the two modules |

See [the architecture](ARCHITECTURE.md) for execution and persistence boundaries.
