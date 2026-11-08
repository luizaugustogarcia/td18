# Research plan and proof completion

The objective is a checked upper bound of ten transpositions for every
ordinary order-18 instance. Together with TD(17) = 10 and distance-preserving
adjacency extension, this would establish TD(18) = 10. The upper bound is
**not yet proved**. [The source paper](td18-reduction-count.tex) specifies the
joint-selection argument; the following artifacts are required to complete it.

## 1. Check the mathematical coverage

Validate the cyclic-target convention, fixed-point contraction, twisted
class correspondence, and independently established diameter premises.
Check the complete consecutive-triple characterization of moves creating
at least two fixed points, including even-length algebraic cycles. Every
qualifying gain-one first move must be considered. Totally unoriented states
have no structural outgoing edge at any depth.

Independently reproduce the 53 fixed-point-free even partitions of 19 and
the 104 fixed-point-free even endpoint annotations on 16 symbols with
3-norm at most seven. Check the 52 oriented-3 endpoint closures. Verify the
cited local support-clearing premises at powers 7:4, 9:7, 11:6, and
13:7/10/11 and their global budgets. No unlisted clearing is assumed.

Exit criterion: a checked coverage derivation agreeing with the paper and
both implementations, including the complete inverse construction.

## 2. Check counts and generation

The completed root inventory is:

| Category | Classes |
| --- | ---: |
| Totally unoriented roots | 214,474,050 |
| Oriented joint residuals before support clearing | 37,858,324 |
| Support-cleared residuals | 4,249,763 |
| Remaining oriented roots | 33,608,561 |
| Direct root obligations | 248,082,611 |

All 53 partitions completed in the native count. Complete native and Java
class sets agree for domains 3 through 10. Singleton-19 symmetry checks agree
on 667 classes. The support scanner validated every listed oriented residual. The production
Java constrained generator also completed all 53 partitions and reproduced
every J/C/O count and the no-qualifying-first-move total. Its versioned artifact
is data/joint-reduction-audit/java-production-counts-v1.jsonl.
Membership and uniqueness checks on 367,169 classes in 16 disjoint root
partitions are additional validation, not an independent full-domain
completeness proof.

Required checks preserve fixed-content encoding, auxiliary standardization,
twisted reflection with rank inversion, and full-target realizability.
Compare generated counts with the paper in every root row, including zeros.
Validate endpoint class counts independently with Burnside/direct comparisons
and reject missing, repeated, malformed, or out-of-scope representatives.

Exit criterion: complete independently checked generation counts for all
root and endpoint scopes, in versioned artifacts with deterministic keys.
Counting alone cannot discharge a sorting obligation.

## 3. Discharge direct roots and endpoints

The 214,474,050 totally unoriented direct roots require complete witnesses
within ten moves. Each of the 33,608,561 remaining oriented direct roots
needs a regenerated valid CPU closure or a complete ten-move witness. Each endpoint outside the 52
elementary types needs a checked result within eight moves or final inverse
coverage. Reused TD(16) witnesses must be replayed; a partial source database
never closes an entire type by its reported count.

A prefix closure must replay applicable transpositions, preserve the target,
contract the resulting fixed points, and satisfy the remaining known-diameter
bound. It is not a complete sorting certificate. Every complete certificate
must finish at the target within its budget. A valid longer witness is a miss
for a smaller budget; malformed witnesses are errors.

Exit criterion: all direct roots have checked results, and every endpoint
has an eight-move result or belongs to the regenerated residual set.
No number or list of endpoint failures is a premise.

## 4. Complete final inverse coverage

For every residual endpoint, restore two fixed points in all cyclic gap
distributions and reverse all supports consisting of both restored symbols
and one original symbol. Check full validity, fixed-point freedom, orientation,
exact forward gain two, and odd-length-cycle increase two. Deduplicate valid
18-symbol predecessor classes and screen with budget-nine CPU closures.

For every remaining predecessor, restore one fixed point in each gap and
reverse every support containing it and two original symbols. Check the
actual qualifying gain-one edge and retain valid oriented roots. Deduplicate
root classes on disk and require budget-ten closure or complete witnesses.
Forward replay, gap enumeration, and transport through class representatives
must establish that every selected root path is covered. Missing obstruction
markers cannot suppress an endpoint or root obligation.

Exit criterion: a complete reconstruction report with counts at both waves,
independent verification of every result, and no unresolved root.

## 5. Independently verify the entire proof

Run the CPU checker against the finished certificate database without phase
or range omissions. It must independently regenerate inventories, classes,
and inverse waves, enforce every count and budget, replay every required
witness, and reject missing data. Checksums and sidecar manifests are not
proof inputs. Structural tests and partial checks establish only their scope.

Exit criterion: one complete successful end-to-end report covering every
obligation in the paper and the independently cited premises.

Full sorting and this end-to-end report remain pending. Implementation tests
and completed counting audits do not replace them. Generated databases,
checkpoints, logs, and certificates must remain untracked.
