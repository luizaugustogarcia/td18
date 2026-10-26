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

## The exceptional oriented 9-cycle extensions

Let beta0 = (0 1 ... 8) and gamma = (0 5 1 6 2 7 3 8 4) = beta0^5.
The local target gamma * beta0 = beta0^6 has three cycles. Thus this is an
unchecked candidate orbit that can become realizable when interleaved with
companions. The TD(16) local classification identifies it as the unique
exception to its qualifying transposition or (5,4)-sequence route; that
repository's extension certificates cover only 17 symbols.

The TD(18) branch fixes the local order of gamma on its nine-symbol support
and interleaves all companions on the other ten symbols. Even permutation
parity leaves six companion partitions; their full orientation multisets
give 18 root types. The generator prescribes gamma's ranks in induced beta
order as 0,2,4,6,8,1,3,5,7, using zero-based ranks along gamma. Twisted
reflection reverses that subsequence and inverts ranks, preserving the same
progression after choosing the rank origin. Ordinary reversal alone does
not express that action.

The existing direct orbit comparisons therefore apply to this invariant
family, and target validation still rejects noncyclic candidates. Independent
small interleaving tests compare candidate-orbit deduplication using
TwistedBraceletKey and valid class sets using ExtendedToricClassKey; tests
also reproduce the four checked TD(16) realizable counts. This avoids requiring
intermediate companion deletions to preserve realizability.

Every represented 19-symbol class requires a complete sorting witness within
ten transpositions. The checker regenerates every required class and validates
its witness directly from the certificate database. Fixed-point roots use
the established contraction bound. Full emission and verification of all 18
types remain pending; see [the operations guide](OPERATIONS.md#emit-and-check-exceptional-9-cycle-extensions).

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

### Two-reduction states and retained obligations

Let a state be (T, s), where T is the contracted annotated type and s is the
number of transpositions already used. Let N be its number of nonfixed
symbols. Its remaining budget is 10 - s. The
source document specifies known-diameter closure and the reduction priorities,
with a depth cap of two for recursive transposition branches and fresh child
annotations. The reduction families come from the shared manuscript's
oriented-3 corollary, odd-length-cycle reduction proposition, and even-pair
proposition in [its TD(16) argument](../../td/docs/paper/main.tex). The present
priorities account for TD(18)'s budgets; the manuscript's U/T/E9/O/E priority
table applies to its 17-symbol domain.

At every depth, test the known-diameter bound first. If the state remains
open and every annotation in its type is unoriented, retain it as a direct
obligation and apply no structural reduction. This is a routing-policy
stop; it does not assert that sorting transpositions are unavailable. The
following reduction rules apply only when some cycle is oriented.

Orientation does not guarantee a qualifying 2-move. For lengths
`k = 5, 7, 9, 11, 13, 17`, the cited odd-length-cycle reduction supplies either
a qualifying 2-move or a support-clearing sequence of at most `(k+1)/2`
transpositions, apart from the specified local 9-cycle exception. Clearing
closes a state only when `s + (k+1)/2 + TD(N-k-1) <= 10`. Thus root
`(13O,3U,3U)` closes its no-qualifying-move branch with `7 + TD(5) = 10`,
while its qualifying 2-moves lead to freshly annotated children. By contrast,
clearing a root 5-cycle gives `3 + TD(13) = 11`, so another route is required.
Length 15 is absent from that cited classification because the TD(16)
argument excludes it by parity on 17 symbols. The independent local
classification below handles that length for the present domain.

After the oriented-3 rule, inspect oriented odd-length cycles in increasing
order of distinct length. Include their qualifying 2-move branches and stop fallback exploration
when a cited support-clearing alternative fits the budget. The exceptional
9-cycle and 19-cycle root cases have the separate certificate branches
described above; the exceptional 15-cycle uses its restricted companion
interleavings below. Otherwise continue with later oriented cycles and the
even-pair fallback. That fallback requires some oriented cycle in the type,
but the two selected even-length cycles may both be unoriented. For example,
`(5O,4U,4U,3U,3U)` still has this fallback if its 5-cycle lacks a qualifying
move. If none covers the case without a qualifying move, retain
the type while continuing its conditional branches. Rows 205–292 add
51 root obligations and 37 one-move obligations to the original rows 1–204.
Table 2 is the complete inventory of 292 retained types. Its R and Q
columns serve different purposes; [the workflow](WORKFLOW.md) explains how
each becomes a computational obligation.

### The exceptional oriented 15-cycle

Let `beta_15 = (0 1 ... 14)` be the current cycle induced on the selected
support. The independent checker extends the shared paper's half-turn and
qualifying-move criteria to length 15. Its exhaustive DFS visits 169,355
nodes and retains exactly `beta_15^14` and `gamma = beta_15^8`. The first is
unoriented. Thus the unique oriented local configuration without a
qualifying transposition is represented by

~~~text
gamma = (0 8 1 9 2 10 3 11 4 12 5 13 6 14 7).
~~~

This is a cyclic-target candidate: `gamma * beta_15 = beta_15^9` has three
cycles. It cannot have a support-clearing sequence at any length. Every
applicable transposition supported there preserves that local product,
while the restricted current permutation remains one cycle. Clearing
`gamma` would identify that cycle with a three-cycle product. The
[classification test](../checker/src/test/java/br/unb/cic/td18/verification/OrientedFifteenCycleClassificationTest.java)
reproduces the exhaustive classification, checks all 455 local applicable
transpositions, and verifies a realizable `(15O,3U)` companion interleaving.

The separate computational branch fixes this local pattern and generates
only its companion interleavings. Its standardized rank progression is
`(2*j mod 15) + 1` at occurrence `j`, starting at zero. The same progression
is preserved by twisted reflection and auxiliary rank standardization, so
the restriction is defined on candidate orbits. The two obligations are:

| State | Candidate bracelets | Realizable classes | Remaining budget |
| --- | ---: | ---: | ---: |
| `(15O,2U,2U)`, depth 0 | 360 | 90 | 10 |
| `(15O,3U)`, depth 1 | 27 | 7 | 9 |

The dedicated generator and checker cover these restricted classes;
`(15O,3U)` is consequently absent from Table 2 as a whole-type obligation.
Every other oriented 15-cycle supplies a qualifying transposition, and an
oriented 3-cycle companion closes through its usual clearing reduction.
The counts are checked by direct generation and independent fixed-content
interleavings. CPU search finds a prefix that closes 86 of these classes;
independent replay checks applicability, target preservation, fixed-point
gain, and the known diameter at every selected class:

| Classes | Prefix length | Created fixed points | Global bound |
| --- | ---: | ---: | --- |
| 74 roots | 3 | 6 | `3 + TD(12) = 10` |
| 5 roots | 2 | 4 | `2 + TD(14) = 10` |
| All 7 intermediate classes | 3 | 6 | `1 + 3 + TD(11) = 10` |

Each proof run regenerates these prefixes. The seven intermediate classes
therefore need no complete sorting certificate. The remaining 11 root
classes have no closure from this bounded prefix search and still require
complete ten-transposition witnesses. Their certificate checks remain
pending. A prefix-search miss does not establish a lower bound.

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
| Exceptional 15-cycle classification and interleavings | checker’s verification/OrientedFifteenCycleClassification.java; model/ExceptionalFifteenCycle.java; bracelet/DirectTwistedBraceletGenerator.java |
| CPU prefixes and emitted searches | search/CommonOperations.java; emitter’s search/WitnessSearch.java |
| Witness encoding and replay | certificate/CertificateCodec.java, certificate/CertificateValidator.java |
| Predecessor reconstruction | avoidance/ObstructionAvoidance.java, independently implemented in the two modules |

See [the architecture](ARCHITECTURE.md) for execution and persistence boundaries.
