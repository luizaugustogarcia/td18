#!/usr/bin/env python3
"""Count the exceptional-nine root candidate orbits, without sorting searches.

The count bounds realizable classes because target realizability is not imposed.
The proof of the Burnside terms is in docs/two-cycle-residual-route.md.
Print a versioned JSON report; generated reports remain untracked.
"""

from collections import Counter
import json
from math import comb, factorial, prod


PARTITIONS = [(8, 2), (7, 3), (6, 4), (5, 5), (4, 2, 2, 2), (3, 3, 2, 2)]
EXPECTED = [551411280, 420076800, 367627680, 176456448, 45963540, 61276320]


def involutions(size):
    images = list(range(size))

    def generate(unassigned):
        if not unassigned:
            yield tuple(images)
            return
        first, *remaining = unassigned
        images[first] = first
        yield from generate(remaining)
        for second in remaining:
            images[first], images[second] = second, first
            yield from generate([symbol for symbol in remaining if symbol != second])
            images[second] = second
        images[first] = first

    yield from generate(list(range(size)))


def cycle_lengths(images):
    unseen = set(range(len(images)))
    lengths = []
    while unseen:
        symbol = min(unseen)
        length = 0
        while symbol in unseen:
            unseen.remove(symbol)
            symbol = images[symbol]
            length += 1
        lengths.append(length)
    return tuple(sorted(lengths, reverse=True))


def count():
    for size, expected in [(0, 1), (1, 1), (2, 2), (3, 4), (4, 10), (5, 26)]:
        generated = list(involutions(size))
        assert len(generated) == len(set(generated)) == expected
        assert all(all(images[images[i]] == i for i in range(size)) for images in generated)
    all_involutions = list(involutions(10))
    assert len(all_involutions) == len(set(all_involutions)) == 9496
    assert all(all(images[images[i]] == i for i in range(10)) for images in all_involutions)
    reflection = tuple(i ^ 1 for i in range(10))
    fixed_types = Counter(cycle_lengths(tuple(images[reflection[i]] for i in range(10)))
                          for images in all_involutions)
    rows = []
    for partition, expected in zip(PARTITIONS, EXPECTED):
        centralizer = prod(length ** multiplicity * factorial(multiplicity)
                          for length, multiplicity in Counter(partition).items())
        identity_fixed = comb(19, 9) * factorial(10) // centralizer
        reflection_fixed = comb(9, 4) * fixed_types[partition]
        numerator = identity_fixed + 19 * reflection_fixed
        assert numerator % 38 == 0
        candidate_orbits = numerator // 38
        assert candidate_orbits == expected
        rows.append({"companion_partition": partition, "identity_fixed": identity_fixed,
                     "nonidentity_rotation_fixed": 0, "one_reflection_fixed": reflection_fixed,
                     "candidate_orbits": candidate_orbits})
    total = sum(row["candidate_orbits"] for row in rows)
    assert total == 1622812068
    return {"format": "td18-exceptional-nine-candidate-bound-v1", "domain": 19,
            "rows": rows, "candidate_orbits": total, "valid_class_upper_bound": total,
            "realizability_imposed": False, "sorting_witnesses_checked": False}


if __name__ == "__main__":
    if not __debug__:
        raise RuntimeError("Verification requires assertions; run Python without -O")
    print(json.dumps(count(), indent=2, sort_keys=True))
