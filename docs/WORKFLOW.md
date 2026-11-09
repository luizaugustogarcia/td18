# Computation workflow

The route is specified in [the paper](td18-reduction-count.tex). Counting,
sorting, and proof verification are separate stages. The completed root count
is 248,082,611; the additional final reconstruction count is not yet known.

## 1. Validate the mathematical inventory

~~~bash
tools/td18 test
tools/td18 inventory
tools/td18 structure
~~~

These CPU commands check the 53 root partitions, 104 endpoint annotations,
52 oriented-3 endpoint closures, the joint-move predicates, and small-domain
generation/reconstruction tests. A structural check does not certify sorting.
The checker uses its own inventory and generation code.

## 2. Count the generated classes

~~~bash
tools/td18 count -Dexec.args=--phase=roots
tools/td18 count -Dexec.args=--phase=endpoints
~~~

Counting uses all available CPU cores by default and performs no sorting
search. For each root partition it checks the totally unoriented count,
the constrained oriented count before support clearing, the cleared subset,
and the remaining oriented count against the paper. Endpoint counting uses
the full annotated inventory. The native counting audit in
[the operations guide](OPERATIONS.md) provides an independent implementation.

## 3. Emit roots and endpoints

On a configured CUDA machine:

~~~bash
tools/td18 emit -Dtd18.args=--phase=roots
tools/td18 emit -Dtd18.args=--phase=endpoints
~~~

All direct roots use budget ten and require a persisted complete witness or
known-diameter closure. Endpoint types with an oriented 3-cycle close by
1 + TD(12) = 8. For the other types, the emitter replays available witnesses,
then tries a bounded CPU prefix before searching on GPU within eight moves, checking the known-diameter exit at
every depth. Valid TD(16)
witnesses may be imported through --td16-db=PATH; every reused witness is
replayed. An endpoint search miss becomes a residual for final reconstruction,
not a proved lower bound. A missing ten-move root result remains unresolved.

## 4. Resolve endpoint residuals at the end

~~~bash
tools/td18 avoidance
~~~

Regenerate endpoint classes without an eight-move discharge. Restore two
fixed points and reverse the second move, search deduplicated 18-symbol
predecessors within budget nine (CPU prefix, then GPU) and persist successful discharges, then restore one fixed point and reverse
the first move. Each remaining deduplicated root needs a ten-move closure
or complete witness. All gap distributions and admissible supports are
covered, including moves within even-length cycles. Missing obstruction
markers cannot omit obligations.

Inverse generation runs across the available CPU cores; intermediate and
root searches use the configured td18.twistedBraceletWorkers pool with
bounded queues. A sealed intermediate checkpoint can resume through
tools/td18 avoidance with --resume-work=PATH; see the operations guide for
the checked inventory and fresh-work requirements.

The default emit phase is all: roots, endpoints, then final reconstruction.
Rerunning regenerates the selected scope and reuses valid stored witnesses.

## 5. Independently verify the completed database

~~~bash
tools/td18 verify
~~~

The CPU checker independently regenerates all required classes and inverse
waves, checks counts, replays stored complete witnesses and diameter closures.
An absent, malformed, over-budget, or unresolved required result prevents
completion. Sidecar files and checksums are not proof inputs.
A phase or range selection proves only its selected scope. Only the complete
report meeting [every exit criterion](RESEARCH_PLAN.md) permits the
TD(18) = 10 conclusion.
