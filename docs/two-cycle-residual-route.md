# Restricting rows 267, 270, and 274 to simultaneous local failures

The active route replaces the three whole-type searches
by **1,884 restricted seeds and at most 5,188,536 inverse attempts at roots**.
The other Table 2 obligations contain **45,498,332,632** classes. This brings
the table workload within a few dozen billion without requiring a new local
structural reduction or a universal nine-transposition sorting theorem.

[td18-reduction-count.tex](td18-reduction-count.tex) is the source of truth
for this active 292-row route. Table 2 preserves every row and whole-type
`R` count, while `Q` is 391, 321, and 1,172 for the three restricted rows.
The emitter constrains generation and lifts all seeds directly to roots;
the checker independently regenerates the local classification, seed sets,
and root obligations. Complete root certificates and the full TD(18)
verification remain pending.

## Why the large counts are avoidable

Let `(omega, beta)` be a cyclic-target pair with target `rho = omega * beta`.
Let `T` be one of the following contracted, fixed-point-free annotated types,
reached after one transposition. Each has domain size `N = 18`, 3-norm eight,
and remaining budget nine. The annotations O and U mean oriented and
unoriented relative to the current cycle `beta`.

| Table 2 row | Type | Whole-type class count R | Restricted valid classes Q |
| --- | --- | ---: | ---: |
| 267 | `(13O, 5O)` | 284,235,604,416 | 391 |
| 270 | `(11O, 7O)` | 243,117,785,088 | 321 |
| 274 | `(9O, 9O)` | 115,561,900,003 | 1,172 |
| Total | | 642,915,289,507 | 1,884 |

A qualifying transposition is a 2-move that creates a fixed point. The
current reduction policy already enumerates its child types whenever such a
move is available on either oriented cycle. For these three rows no
support-clearing alternative fits the budget, so all their applicable
odd-length-cycle branches are included. In row 274 both cycles must be
tested, even though they have equal length.

Therefore a direct obligation is needed only when **neither cycle has a
qualifying transposition**. The route preserves all qualifying child branches
and retains only this restricted family for direct root lifting.
Every instance is then covered: it either takes an existing qualifying
branch, or belongs to that family. No new outgoing structural edge from a
totally unoriented type is introduced.

## Exhaustive local classification

For an algebraic cycle `C` of length `k`, let `beta_C` be the restriction of
the current cycle to its support. A transposition supported there is
applicable exactly when it is applicable to `beta_C`; its change to the
algebraic permutation is `C -> C * tau^{-1}`. Companions therefore do not
affect whether such a transposition is qualifying.

The shared framework's qualifying-move lemma, half-turn corollary, and
local classification are in
[the shared paper](../../td/docs/paper/main.tex), labels
`lem:qualifying-criterion`, `cor:half-turn`, and `tab:local-exceptions`.
Relabel the local current cycle as `beta_k = (0,...,k-1)`.
The oriented local cycles without a qualifying move are exactly these powers:

| Length `k` | Powers `C = beta_k^e` | DFS nodes, independently reproduced |
| ---: | --- | ---: |
| 5 | `e = 3` | 14 |
| 7 | `e = 4` | 80 |
| 9 | `e = 5, 7` | 506 |
| 11 | `e = 6` | 3,391 |
| 13 | `e = 7, 10, 11` | 23,619 |

The independent Python audit enumerates normalized cycle writings beginning
with zero, applying the cited necessary conditions and checking wrapped
conditions on complete writings. Every retained cycle is checked against
every applicable local transposition by direct multiplication. Each search
also retains exactly `beta_k^{-1}`, which is unoriented and excluded here.
Unpruned enumeration independently agrees for lengths 5, 7, and 9.

These are local **candidates**, not necessarily cyclic-target pairs. In
particular, `beta_9^5 * beta_9` has three cycles. The row-274 count retains
its full-domain realizable interleavings; it does not assume that the
exceptional nine-cycle root route already covers intermediate states.

## Fixed-content encoding, symmetry, and counts

Assign one color to each algebraic cycle, occurring its prescribed number
of times in current-cycle order. An oriented bead also carries its cyclic
rank along the algebraic cycle. If `C = beta_k^e`, its zero-based ranks in
local current-cycle order are `0, h, 2h, ...` modulo `k`, where `h` is the
multiplicative inverse of `e` modulo `k`. The allowed rank steps are thus
`{2}` at lengths 5, 7, and 11; `{2,4}` at length 9; and `{2,4,6}` at length 13.

Auxiliary standardization sets each first rank to zero and names equal
annotated colors by first occurrence. Rotation preserves each progression
up to rank origin. Twisted reflection reverses word positions **and**
inverts cyclic ranks, so it also preserves the progression. It is not
ordinary reversal of the ranked word. Consequently the restricted family
is invariant under the shared auxiliary and twisted dihedral action.
By the shared orbit correspondence, its twisted bracelets are candidate
orbits, and its realizable orbits are extended-toric classes.

An elementary complete enumeration chooses the support of the first cycle
among the 18 current-cycle positions, takes the complement for the second,
and assigns the prescribed powers. For equal lengths use unordered exponent
pairs. When the two exponents also agree, complementary supports describe
the same permutation and are identified. When they differ, the exponent
distinguishes the supports. Quotient by the dihedral action and require
`rho = omega * beta` to be a full 18-cycle.

| Row | Exponents on the displayed cycle lengths | Raw candidates | Candidate orbits | Valid classes |
| ---: | --- | ---: | ---: | ---: |
| 267 | `(7,3)` | 8,568 | 252 | 96 |
| 267 | `(10,3)` | 8,568 | 252 | 115 |
| 267 | `(11,3)` | 8,568 | 252 | 180 |
| 270 | `(6,4)` | 31,824 | 912 | 321 |
| 274 | `(5,5)` | 24,310 | 765 | 193 |
| 274 | `(5,7)` | 48,620 | 1,387 | 439 |
| 274 | `(7,7)` | 24,310 | 765 | 540 |
| Total | | 154,768 | 4,585 | 1,884 |

The Python audit compares support-mask orbits with direct permutation
rotation and twisted reflection for every restricted orbit. The Java audit
independently enumerates support subsets, uses `TwistedBraceletKey` for
candidate deduplication, validates with `asCyclicTargetPair()`, and uses
`ExtendedToricClassKey` for valid pairs. Both reproduce every count above.
They are small exhaustive enumeration audits. The Java audit additionally
compares each complete valid class set with the production direct generator.

Production direct generation constrains both colors' rank progressions
**during prefix construction**, then applies the existing auxiliary
standardization and twisted dihedral comparisons. The invariant-subfamily
argument of the exceptional nine- and fifteen-cycle generators applies:
every restricted orbit has its canonical representative in the subfamily,
and the existing comparisons retain exactly that representative. Check
realizability before emitting it as a class. The generator never constructs
the original 642.9 billion classes for filtering. Fixed-point contraction
precedes this fixed-point-free generator.

## Recover the root budget instead of assuming nine moves suffice

The supplied reference by Cunha, Kowada, Hausen, and de Figueiredo,
*Advancing the Transposition Distance and Diameter through Lonely
Permutations*, [Table 6.2, page 25](<refs/Articles/Sorting/ADVANCING THE TRANSPOSITION DISTANCE AND DIAMETER.pdf>),
reports distance ten for `u_{4,4} union u_{12,5}`. Definition 4.1, page 14,
gives this ordinary permutation:

```text
[4,3,2,1,5,10,15,7,12,17,9,14,6,11,16,8,13]
```

Let `beta = (0, pi_1, ..., pi_17)` and `rho = (0,...,17)`.
Direct multiplication gives

```text
omega = (0 14 10 6 15 11 7 16 12 8 17 13 9)(1 3 5 2 4).
```

Relative to their induced current cycles the two components are powers
`beta_13^7` and `beta_5^3`. The Python audit checks this membership in row
267's restricted family. The reported distance-ten lower bound is cited
computational evidence, not independently recertified here. It rules out
relying on an unrestricted nine-move theorem for this family if the cited
result is correct.

The existing CPU prefix closure was also run on all 1,884 canonical valid
classes with budget nine; it closed zero. A prefix miss does not prove that
nine-move sorting is impossible. It leaves a full-witness or root obligation.

**The active route skips these nine-move searches entirely and lifts
all 1,884 classes to roots.** This is conservative: easy classes may be
lifted unnecessarily, but no failed bounded search is needed as a premise.

Each class has `N = 18` after exactly one move from a fixed-point-free
19-symbol root. Hence exactly one algebraic fixed point was contracted.
Restore it, with a single fresh label, in each of the 18 current-cycle
gaps. For each restoration, consider every support consisting of that
symbol and two of the original 18 symbols. There is one applicable cyclic
ordering per support in the restored current cycle. Thus there are at most

```text
18 * binomial(18,2) = 2,754 inverse attempts per restricted class;
1,884 * 2,754 = 5,188,536 inverse attempts in total.
```

Choosing old symbols from different child cycles covers inverse odd-cycle
splits. Choosing them from the same child cycle covers inverse even-pair
reductions. Their counts sum to `a*b + binomial(a,2) + binomial(b,2) =
binomial(18,2)` when `a+b=18`. No inverse two- or three-fixed-point
restoration can lead to a root on 19 symbols from this domain.

Every actual predecessor is included: extend a relabeling from its
contracted successor to the restored symbol, choose its cyclic gap, and
reverse its actual transposition support. Contraction and the twisted
action preserve this correspondence, so using one representative per
successor class loses no predecessor class. Require forward replay,
fixed-point-free root status, and eligibility under the reduction policy;
then deduplicate by extended-toric class. The existing
`ObstructionAvoidance.reconstructParentsInto`, `reconstructOddSplit`,
`reconstructEvenPair`, and `forEachRestoration` already implement these
inverse families. This bound is before their filtering and deduplication;
it is not a measured root count or a runtime estimate.

Sort the resulting roots directly within ten transpositions, or use
checked prefixes followed by known diameters. Do not prepend the original
reduction to a ten-move successor witness: that would cost eleven. Missing
root witnesses remain unresolved obligations.

## Workload and proof completion

Subtracting the three whole-type counts from the previous unrestricted Q
total gives the unchanged contribution of every other Table 2 row:

```text
688,413,622,139 - 642,915,289,507 = 45,498,332,632 classes.
```

Adding the three restricted counts gives **Q = 45,498,334,516**. Their
1,884 seeds require at most **5,188,536** root inverse attempts before
filtering and deduplication. The distinct root count and the number of root
prefix misses are outcomes of reconstruction and screening, not these bounds.

The separate exceptional-fifteen branch has exactly 97 classes: 90 roots
and 7 intermediate states. Its 86 checked CPU prefix closures leave **11
root witnesses** to certify. The exceptional-nineteen branch has exactly
**2 classes**, with explicit ten-move witnesses independently replayed in
tests and still required in the completed proof database. The exceptional-nine
branch covers 18 root annotations and has exactly **1,622,812,068 candidate
orbits**, so at most that many realizable root classes. This is a checked
upper bound; its exact realizable count and certificate run remain pending.
Other table rows' bounded-search misses
also require avoidance; their eventual distinct root count is not known
before the complete run. These quantities are additional to Q, and the
45.5-billion figure is not a bound on the complete proof runtime.

The production implementation covers the complete route:

1. The source keeps all 292 rows and 4,617 structural edges. Whole-type R
   values remain unchanged, while the three restricted Q values are checked
   by both production and independent reduction/count validation.
2. The direct generator enforces both local progressions during construction,
   checks realizability, and emits exactly the audited class sets. Each seed
   enters root reconstruction directly; no nine-move search is a premise.
3. The independent checker regenerates the local classifications and support
   interleavings, compares complete seed sets, reconstructs every admitted
   parent, and requires checked CPU closure or a ten-move root witness.
   Seed obstruction markers, work databases, count reports, sidecars, and
   checksums cannot replace these proof checks.
4. Standalone avoidance regenerates every seed, including when its marker is
   missing. Mixed source budgets eight, nine, and ten and exact resume
   inventories preserve both direct seeds and unresolved roots. Row manifests
   use version 4, avoidance manifests version 7, and the work database version
   5; incompatible old completion formats are rejected.
   Immutable work-source records survive deletion of resolved obstruction
   markers. Unresolved root searches can be retried, and interrupted retries
   invalidate their completion records before altering the frontier.

Implementation and small exhaustive audits establish coverage and generation.
The full root sorting run and independent certificate verification remain
pending. The completion criteria in [RESEARCH_PLAN.md](RESEARCH_PLAN.md)
continue to apply; this route does not establish TD(18) = 10 by itself.

### Bound for the separate exceptional-nine branch

The six companion partitions allow every orientation, so count all companion
permutations of each partition. Let `m_j` be the multiplicity of length `j`
and define `z = product_j j^m_j * m_j!`. Choosing the exceptional support and
then its companions gives `I = binomial(19,9) * 10! / z` candidates with
current cycle `beta = (0,...,18)`. The exceptional nine-cycle is uniquely
prescribed on each support.

No nonidentity rotation fixes a candidate: 19 is prime, so commuting with
such a rotation would make the algebraic permutation a power of beta,
which cannot have the required nine-cycle and companions. A reflection has
one fixed symbol and nine pairs of exchanged symbols. An invariant nine-symbol support
contains its fixed symbol and four of those pairs, giving `binomial(9,4)`
choices; the prescribed exceptional cycle is fixed by twisted reflection.
On the remaining ten symbols the reflection `f` is five disjoint 2-cycles.
The companion permutation `pi` is fixed by twisted reflection exactly when
`pi*f` is an involution. Enumerate all 9,496 involutions `t` on ten symbols,
set `pi=t*f`, and count those with each companion partition. All 19
reflections are conjugate. If `F` is this full reflection-fixed count,
Burnside's identity gives `(I + 19*F)/38` candidate orbits.

| Companion lengths, all annotations | Candidate orbits, hence upper bound on valid classes |
| --- | ---: |
| (8,2) | 551,411,280 |
| (7,3) | 420,076,800 |
| (6,4) | 367,627,680 |
| (5,5) | 176,456,448 |
| (4,2,2,2) | 45,963,540 |
| (3,3,2,2) | 61,276,320 |
| Total | 1,622,812,068 |

The independent small enumeration in
[verify_exceptional_nine_bound.py](../tools/research/verify_exceptional_nine_bound.py)
checks the involution generator on small domains, verifies every ten-symbol
involution, and reproduces all Burnside terms. It does not test target
realizability or search for sorting witnesses. These bounds must not be
reported as measured valid-class counts.

## Reproduce the audits

From the repository root, with Python 3.10+ and JDK 21:

```bash
python3 -B tools/research/verify_two_cycle_residuals.py --check-all-actions > /tmp/td18-two-cycle-python.json
python3 -B tools/research/verify_exceptional_nine_bound.py > /tmp/td18-exceptional-nine-bound.json
mvn -pl emitter -am test
mvn -q -pl emitter dependency:build-classpath -Dmdep.outputFile=/tmp/td18-two-cycle-classpath.txt
td18_audit_cp="emitter/target/classes:$(cat /tmp/td18-two-cycle-classpath.txt)"
java -Xmx1g --class-path "$td18_audit_cp" tools/research/CheckTwoCycleResiduals.java --prefix > /tmp/td18-two-cycle-java.json
```

The Python audit checks all 292 row numbers, the source table's three restricted
rows, and both totals before reporting the workload calculation. The Java
audit fails on unexpected counts, overlapping families, an incomplete combined
inventory, or any difference from the production direct class sets. Omit
`--prefix` to run its generation/key check alone. Both reports have versioned
formats; reports and any future databases or certificates remain untracked.
