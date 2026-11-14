# Active route architecture

The [paper](td18-reduction-count.tex) specifies the mathematics and
[research plan](RESEARCH_PLAN.md) states proof completion criteria. The public
`WitnessEmitter` and `CertificateChecker` entries dispatch to their active
`ErikssonWitnessEmitter` and `ErikssonCertificateChecker` implementations.
The Maven modules remain independent: the checker has no emitter dependency.

`ErikssonRoute` defines the root domain 19, budget 10, endpoint domain
14, budget 7, and 74 endpoint annotations. `ErikssonInventory`
reads every active paper row in deterministic partition order. Explicit pending
rows support structure checks but cannot supply proof counts.

The emitter root stream uses `ErikssonRootGenerator` with normalized algebraic
cycles and prefix placement. `CyclicTargetMoves` contains mathematical move and
contraction operations. The checker has its own independent move implementation
and a budgeted `SearchState`; neither requires a superseded route container.
Source-side immediate and three-move constraints, forced target-cycle checks,
and twisted canonical comparisons prune prefixes. Known directed target paths
also certify immediate target-side closures. Full remaining leaves pass the
complete two-sided reduction test. Count mode retains no representative set.

The checker derives its own source predicates by arbitrary supports, uses
independent rank-placement state, and tests both full cycles directly. Its
selection does not call the emitter's endpoint-swapping or consecutive-triple
helpers. Both implementations are compared with independent small-domain
permutation oracles before large runs.

Endpoint classes use the existing fixed-content twisted-bracelet generator and
Burnside counter. Both modules independently regenerate the complete annotated
cover. Elementary diameter bounds close whole families; every other class
requires a persisted complete witness, replayed diameter prefix, or final inverse
coverage.

`ErikssonInverseReconstruction` enumerates inverse gains zero through three on
both endpoints, restoring all deleted fixed symbols in all gaps. The emitter
uses image arrays and simultaneous gap choices. The checker uses permutation
multiplication and sequential insertion. Small-domain tests compare complete
predecessor class sets for every gain on both cycles.

`ErikssonAvoidance` performs three disk-backed waves: reverse gain two, reverse
both gains two/three, then reverse gains one/zero. Lookups and searches use each
state's explicit domain and budget; both after-one domains have budget 9.
Every final root uses 10. CPU sorting prefixes and intermediate GPU searches
are heuristics: a miss, depth limit or timeout preserves every inverse obligation.
Intermediate CUDA searches default to exploration depth 5, controlled by
`td18.reconstructionGpuDepth`, while diameter closures retain the full budget.
`td18.reconstructionGpuMillis` defaults to 0 (no time cutoff). Budget-10 searches
always use full exploration depth 10 without a time cutoff.
Persistence is bounded and asynchronous. The checker
regenerates all waves from actual missing endpoint discharges, so missing
obstruction markers cannot suppress work.

CUDA's sorting tree excludes fixed symbols from supports, permits increases of
zero or two in the number of odd-length cycles, and additionally excludes
supports in three distinct algebraic cycles. The CPU prefix search permits
the latter when their odd-length-cycle increase is zero. A GPU search without
a time cutoff and with full exploration depth exhausts only this restricted
tree within its move budget.
Only replayed witnesses discharge a class; exhaustion, depth limits and timeouts all leave
it unresolved.

The kernel also skips a positional transposition `(i,j,k)` immediately followed
by `(i,i+k-j,k)`. These two segment interchanges return to the identical
cyclic-target pair. Deleting the adjacent inverse moves preserves any later
closure and saves two moves, so the skip preserves existence of a certificate
within the obligation budget.

Sealed checkpoints are versioned computation state. The latest-wave count,
route and budgets are validated on resume. Intrinsic certificates remain
route-independent and are replayed at the requested budget. Counts, successful
partial scopes, checkpoints and passing tests are distinct from a complete
end-to-end proof report.
