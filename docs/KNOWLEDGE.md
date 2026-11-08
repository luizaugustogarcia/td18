# Mathematical knowledge and references

[The TD(18) paper](td18-reduction-count.tex) specifies joint selection and the
complete root inventory. The [shared paper](../../td/docs/paper/main.tex),
Section sec:twisted-bracelets, supplies the bracelet framework and established
conventions. [The research plan](RESEARCH_PLAN.md) lists pending proof artifacts.

## Pairs, moves, and fixed points

Let N = n + 1. A cyclic-target pair is (omega, beta), where beta is the current
N-cycle, omega is even, and rho = omega * beta is the target N-cycle. Sorting
means reaching omega = identity and beta = rho. Validate a
CyclicTargetCandidate using asCyclicTargetPair() or requireCyclicTargetPair().

An applicable transposition is a triple (a,b,c) in cyclic beta order. It acts
by omega' = omega * tau inverse and beta' = tau * beta, preserving the target.
A 2-move increases the number of odd-length algebraic cycles by two; it is
qualifying if it also creates a fixed point. A 2-cycle is an algebraic object,
not a sorting move. The 3-norm is (N - number of odd-length cycles)/2, including
fixed points in that count. A qualifying move decreases it by one.

For a cycle gamma with support S, let beta_S be beta restricted to S. The
cycle is unoriented exactly when gamma = beta_S inverse. All other cyclic
orders are oriented; every 2-cycle is unoriented. An annotated type records
lengths and orientations with equal annotations indistinguishable.

Fixed points are explicit data in the general model, including singletons
omitted from a multicycle writing. Contraction preserves distance and 3-norm
(shared Propositions prop:adjacency-fixed-point and
prop:contraction-preserves-distance). The fixed-point-free encoding and
generator are used only after contraction. They do not assert a generator
for arbitrary singleton content.

## From fixed content to one representative per class

For a fixed-point-free annotated type, a color identifies an algebraic cycle.
Unoriented colors contribute repeated unranked beads, and oriented colors
contribute each cyclic rank once. Positions follow beta. Ranked orders that
decode to the unoriented order are excluded. This is the fixed-content encoding
of Proposition prop:fixed-content-encoding in the shared paper.

Auxiliary symmetries rename equal annotated colors and shift oriented rank
origins. StandardizedEncodingWord names colors by first occurrence and sets
the first oriented rank to zero. Internally, zero denotes an unranked bead;
mathematical cyclic ranks are stored with an offset of one.

Twisted reflection reverses word positions and inverts oriented ranks.
Rotation and this reflection define the twisted dihedral action; ordinary
word reversal is insufficient. A twisted bracelet is the orbit itself.
Decoder equivariance and realizability invariance identify realizable
bracelets with extended-toric classes (shared Theorem
thm:bracelet-class-correspondence). TwistedBraceletKey identifies candidate
orbits; ExtendedToricClassKey identifies valid-pair classes.

DirectTwistedBraceletGenerator compares standardized prefixes under these
symmetries and checks full-target realizability. The shared Theorem
thm:twisted-generation-correctness gives one representative per realizable
class. Burnside counting independently averages symmetry-fixed realizable
objects; [the counting guide](td18-burnside-counting.md) gives its exact
identity, rotation, and reflection terms. A class count is not a sorting result.

## Joint selection

Totally unoriented roots remain direct ten-move obligations. Every other root
first tests moves creating at least two fixed points, which close by
1 + TD(16) = 10. Otherwise inspect every qualifying first move creating one
fixed point. After contraction, an oriented successor can use a second move
creating three fixed points, closing by 2 + TD(14) = 10, or two fixed points,
reaching 16 symbols with budget eight and 3-norm at most seven. Totally
unoriented successors have no structural outgoing edge.

On a fixed-point-free omega, any move creating two fixed points uses two
consecutive omega edges, so its triple has the form
(x, omega(x), omega squared(x)). Testing at most N such triples is complete,
including even-length cycles. All qualifying gain-one first supports are
checked, without a preferred cycle length. The paper proves this criterion.

The 104 endpoint types cover all fixed-point-free even annotated permutations
on 16 symbols with 3-norm at most seven. The 52 with an oriented 3-cycle close
by 1 + TD(12) = 8. Other endpoint classes need eight-move discharges or final
inverse reconstruction. Imported TD(16) witnesses must be replayed, and missing
eight-move results remain obligations even if obstruction markers are absent.

For oriented roots without a favorable prefix, the constrained generator
assigns current-cycle positions to canonical algebraic labels. Immediate
forbidden triples and forbidden pairs of moves reject prefixes as soon as
at most six required positions are known. Auxiliary cycle standardization,
rotation/twisted-reflection comparisons, and full-target checks leave exactly
one valid representative per residual class.

Support-clearing patterns relative to beta_S are powers 4 at length 7,
7 at length 9, 6 at length 11, and 7/10/11 at length 13. The shared
prop:td16-odd-cycle-reduction certifies their local sequences, and each obeys
(k+1)/2 + TD(18-k) = 10. These explicit patterns remove 4,249,763 roots.
The length-nine power 5 is not included; no length-fifteen clearing is assumed.

The completed direct total is 248,082,611 roots. In particular, only 667
classes of the oriented 19-cycle partition have no favorable prefix: two
have no qualifying first move and 665 do. None is a proved sorting failure.
Endpoint residuals contribute additional final-phase reconstructed roots.

## Inverse coverage and verification

For each endpoint without an eight-move result, restore two fixed points in
all cyclic gaps and reverse every support containing both and one original
symbol. Retain valid oriented fixed-point-free predecessors only after exact
forward gain and odd-cycle checks. Deduplicate and screen within budget nine.
Then restore one fixed point in every gap and reverse every support containing
it and two original symbols. Every remaining valid oriented root requires
budget-ten closure or a complete witness. Labeling restored symbols in current
traversal order loses no classes because relabeling transports all supports.

A regenerated prefix plus a known diameter is a closure, not a complete
sorting certificate. The verifier replays the prefix and checks contraction
and budget. A complete certificate must sort its pair within its budget.
Exhaustive reconstruction, disk deduplication, and regeneration of missing
endpoint obligations prevent a partial database from hiding work.

| Concept | Java location, relative to each module's package root |
| --- | --- |
| Pair validation and class keys | model/ |
| Fixed-content generation and Burnside counting | bracelet/ |
| Diameter premises and joint inventories | joint/ |
| Joint root constraints and support clearing | emitter joint/; checker independent verification code |
| CPU closures and CUDA dispatch | search/; emitter base/ |
| Certificate encoding, storage, and replay | certificate/ |
| Complete inverse waves | avoidance/ |

The package root is br.unb.cic.td18. See [the architecture](ARCHITECTURE.md)
for module and persistence boundaries.
