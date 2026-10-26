#!/usr/bin/env python3
"""Independently count two-cycle candidates with no qualifying local move.

This research check does not change Table 2 or provide sorting witnesses.
Run from any directory with Python 3.10 or newer; no dependencies are needed.
The versioned JSON report goes to stdout. Generated reports remain untracked.

For beta=(0,...,17), each support S=(s_0<...<s_{k-1}) has local
algebraic cycle omega(s_i)=s_{i+e mod k}. A complete local DFS checks which
exponents e can survive the qualifying-move and half-turn criteria of the
shared paper. Every survivor is also checked against all applicable local
transpositions by direct permutation multiplication.

Rotation conjugates omega by a shift of its symbols. Twisted reflection
conjugates omega^{-1} by i -> -i. Both actions preserve e: the reflection
reverses the support order as well as the algebraic cycle. Consequently the
restricted family can be quotiented using dihedral actions on support masks.
For equal lengths and equal exponents, complementary masks encode the same
algebraic permutation and are identified before quotienting. For unequal
exponents their two supports remain distinguished by the exponent.

A candidate is counted as a valid pair only after directly checking that
rho=omega*beta is a full cycle. These restricted candidate orbits correspond
to twisted bracelets, and the valid ones to extended-toric classes, by the
shared framework's orbit correspondence.
"""

import argparse
from collections import Counter
from itertools import combinations, permutations
import json
from math import comb, gcd
from pathlib import Path
import re


EXPECTED_LOCAL = {5: [3], 7: [4], 9: [5, 7], 11: [6], 13: [7, 10, 11]}
EXPECTED_NODES = {5: 14, 7: 80, 9: 506, 11: 3391, 13: 23619}
ROWS = [(267, 13, 5), (270, 11, 7), (274, 9, 9)]
EXPECTED_COUNTS = {267: (756, 391), 270: (912, 321), 274: (2917, 1172)}


def cycle_lengths(images):
    unseen = set(range(len(images)))
    lengths = []
    while unseen:
        symbol = min(unseen)
        length = 0
        while symbol in unseen:
            unseen.remove(symbol)
            length += 1
            symbol = images[symbol]
        lengths.append(length)
    return tuple(sorted(lengths))


def cycle_images(writing):
    images = [0] * len(writing)
    for index, symbol in enumerate(writing):
        images[symbol] = writing[(index + 1) % len(writing)]
    return tuple(images)


def has_qualifying_move(images):
    """Test every beta-applicable transposition on a single odd-length cycle."""
    assert len(images) % 2 == 1 and cycle_lengths(images) == (len(images),)
    initial_odd = sum(length % 2 for length in cycle_lengths(images))
    for a, b, c in combinations(range(len(images)), 3):
        successor = list(images)
        successor[a], successor[b], successor[c] = images[c], images[a], images[b]
        lengths = cycle_lengths(successor)
        if 1 in lengths and sum(length % 2 for length in lengths) == initial_odd + 2:
            return True
    return False


def classify_local(length):
    """Enumerate each normalized cycle writing exactly once before pruning."""
    retained = []
    nodes = 0
    half_turn = (length + 1) // 2

    def ordered(a, b, c):
        return (b - a) % length < (c - a) % length

    def visit(path, used):
        nonlocal nodes
        nodes += 1
        if len(path) == length:
            if (path[0] - path[-1]) % length < half_turn:
                return
            if any(ordered(path[i], path[(i + 1) % length],
                           path[(i + m) % length])
                   for i in range(length) for m in range(2, length, 2)):
                return
            retained.append(cycle_images(path))
            return
        for symbol in range(1, length):
            if symbol in used or (symbol - path[-1]) % length < half_turn:
                continue
            depth = len(path)
            if any(ordered(path[depth - m], path[depth - m + 1], symbol)
                   for m in range(2, depth + 1, 2)):
                continue
            visit(path + [symbol], used | {symbol})

    visit([0], {0})
    assert nodes == EXPECTED_NODES[length], (length, nodes)
    exponents = []
    for images in retained:
        exponent = images[0]
        assert gcd(exponent, length) == 1
        assert images == tuple((i + exponent) % length for i in range(length))
        assert not has_qualifying_move(images), (length, exponent)
        if exponent != length - 1:  # The unique unoriented local cycle.
            exponents.append(exponent)
    exponents.sort()
    assert exponents == EXPECTED_LOCAL[length], (length, exponents)
    assert len(retained) == len(exponents) + 1
    return {"length": length, "visited_nodes": nodes,
            "retained_including_unoriented": len(retained),
            "oriented_exponents": exponents}


def support_images(mask, domain, exponent_a, exponent_b):
    support_a = [i for i in range(domain) if mask & (1 << i)]
    support_b = [i for i in range(domain) if not mask & (1 << i)]
    images = [0] * domain
    for support, exponent in [(support_a, exponent_a), (support_b, exponent_b)]:
        for index, symbol in enumerate(support):
            images[symbol] = support[(index + exponent) % len(support)]
    return tuple(images)


def valid_target(images):
    domain = len(images)
    symbol = 0
    for steps in range(1, domain + 1):
        symbol = images[(symbol + 1) % domain]
        if symbol == 0:
            return steps == domain
    raise AssertionError("The target product is not a permutation")


def support_orbit(mask, domain, interchangeable):
    full = (1 << domain) - 1
    reflected = sum(1 << ((-i) % domain)
                    for i in range(domain) if mask & (1 << i))
    orbit = set()
    for source in (mask, reflected):
        for shift in range(domain):
            moved = ((source << shift) | (source >> (domain - shift))) & full
            if interchangeable:
                moved = min(moved, full ^ moved)
            orbit.add(moved)
    return orbit


def permutation_orbit(images):
    """Independent full-permutation implementation of twisted symmetry."""
    domain = len(images)
    inverse = [0] * domain
    for symbol, image in enumerate(images):
        inverse[image] = symbol
    orbit = set()
    for shift in range(domain):
        rotation = tuple((images[(symbol - shift) % domain] + shift) % domain
                         for symbol in range(domain))
        reflection = tuple((shift - inverse[(shift - symbol) % domain]) % domain
                           for symbol in range(domain))
        orbit.update((rotation, reflection))
    return orbit


def check_small_domains():
    """Check pruning without using it, and masks against permutation actions."""
    for length in (5, 7, 9):
        retained = set()
        for tail in permutations(range(1, length)):
            images = cycle_images((0,) + tail)
            if not has_qualifying_move(images):
                retained.add(images)
        expected = {tuple((i + exponent) % length for i in range(length))
                    for exponent in EXPECTED_LOCAL[length] + [length - 1]}
        assert retained == expected, length
    checked = 0
    for domain, length_a, exponent_a, exponent_b in [
            (6, 3, 1, 1), (6, 3, 1, 2), (8, 5, 3, 2)]:
        interchangeable = 2 * length_a == domain and exponent_a == exponent_b
        full = (1 << domain) - 1
        for support in combinations(range(domain), length_a):
            mask = sum(1 << i for i in support)
            if interchangeable and mask > (full ^ mask):
                continue
            images = support_images(mask, domain, exponent_a, exponent_b)
            by_masks = {support_images(moved, domain, exponent_a, exponent_b)
                        for moved in support_orbit(mask, domain, interchangeable)}
            by_images = permutation_orbit(images)
            assert by_masks == by_images
            assert all(valid_target(member) == valid_target(images) for member in by_images)
            checked += 1
    return {"unpruned_local_lengths": [5, 7, 9],
            "candidate_action_comparisons": checked}


def count_family(length_a, length_b, exponent_a, exponent_b, check_actions):
    domain = length_a + length_b
    interchangeable = length_a == length_b and exponent_a == exponent_b
    full = (1 << domain) - 1
    seen = set()
    raw = 0
    valid_raw = 0
    valid_classes = 0
    orbit_sizes = Counter()
    valid_orbit_sizes = Counter()
    for support in combinations(range(domain), length_a):
        mask = sum(1 << i for i in support)
        if interchangeable and mask > (full ^ mask):
            continue
        raw += 1
        if mask in seen:
            continue
        orbit = support_orbit(mask, domain, interchangeable)
        assert seen.isdisjoint(orbit)
        seen.update(orbit)
        images = support_images(mask, domain, exponent_a, exponent_b)
        if check_actions:
            direct_orbit = permutation_orbit(images)
            assert direct_orbit == {support_images(moved, domain, exponent_a, exponent_b)
                                    for moved in orbit}
            assert all(valid_target(member) == valid_target(images) for member in direct_orbit)
        orbit_sizes[len(orbit)] += 1
        if valid_target(images):
            valid_classes += 1
            valid_raw += len(orbit)
            valid_orbit_sizes[len(orbit)] += 1
    expected_raw = comb(domain, length_a) // (2 if interchangeable else 1)
    assert raw == len(seen) == expected_raw
    assert sum(size * count for size, count in orbit_sizes.items()) == raw
    assert sum(size * count for size, count in valid_orbit_sizes.items()) == valid_raw
    return {"exponents": [exponent_a, exponent_b],
            "raw_candidates": raw, "candidate_orbits": sum(orbit_sizes.values()),
            "raw_valid_pairs": valid_raw, "valid_pair_classes": valid_classes,
            "candidate_orbit_sizes": dict(sorted(orbit_sizes.items())),
            "valid_orbit_sizes": dict(sorted(valid_orbit_sizes.items()))}


def check_literature_example():
    """Check membership, not the cited distance-ten lower bound."""
    ordinary = [4, 3, 2, 1, 5, 10, 15, 7, 12, 17, 9, 14, 6, 11, 16, 8, 13]
    beta = [0] + ordinary
    domain = len(beta)
    inverse_beta = {beta[(i + 1) % domain]: beta[i] for i in range(domain)}
    # rho=(0,...,17), omega=rho*beta^{-1}.
    omega = tuple((inverse_beta[i] + 1) % domain for i in range(domain))
    assert cycle_lengths(omega) == (5, 13)
    # Relabel by beta positions to use the canonical current cycle.
    position = {symbol: index for index, symbol in enumerate(beta)}
    canonical = tuple(position[omega[symbol]] for symbol in beta)
    assert valid_target(canonical)
    support = {0}
    symbol = canonical[0]
    while symbol != 0:
        support.add(symbol)
        symbol = canonical[symbol]
    assert len(support) == 13
    mask = sum(1 << i for i in support)
    assert canonical == support_images(mask, domain, 7, 3)
    return {"ordinary_permutation": ordinary, "row": 267, "local_exponents": [7, 3],
            "membership_checked": True, "reported_distance": 10,
            "distance_lower_bound_rechecked": False,
            "source": "Cunha et al., Advancing the Transposition Distance and Diameter, Table 6.2"}


def check_table_and_workload():
    document = Path(__file__).resolve().parents[2] / "docs/td18-reduction-count.tex"
    table = {}
    for line in document.read_text().splitlines():
        if not re.match(r"\s*\d+ & " + re.escape(chr(92) + "ctype"), line):
            continue
        fields = [field.strip() for field in line.split("&")]
        row = int(fields[0])
        assert row not in table
        annotation = [(int(length), orientation) for length, orientation in
                      re.findall(re.escape(chr(92) + "cyc")
                                 + r"[{]([0-9]+)[}][{]([OU])[}]", fields[1])]
        counts = [int(re.sub(r"[^0-9]", "", value)) for value in fields[2:]]
        table[row] = (annotation, counts)
    assert sorted(table) == list(range(1, 293)), "Incomplete source Table 2"
    expected_whole = {267: 284235604416, 270: 243117785088, 274: 115561900003}
    for row, length_a, length_b in ROWS:
        annotation, counts = table[row]
        assert annotation == [(length_a, "O"), (length_b, "O")]
        assert counts == [18, 1, 1, 9, 8, expected_whole[row], EXPECTED_COUNTS[row][1]]
    total = sum(counts[-1] for _, counts in table.values())
    assert total == 45498334516
    total_r = sum(counts[-2] for _, counts in table.values())
    assert total_r == 692894074401
    restricted_classes = sum(classes for _, classes in EXPECTED_COUNTS.values())
    inverse_attempts = restricted_classes * 18 * comb(18, 2)
    return {"table_rows": len(table), "total_r": total_r, "total_q": total,
            "three_whole_rows_r": sum(expected_whole.values()),
            "other_rows_q": total - restricted_classes,
            "restricted_classes": restricted_classes,
            "root_inverse_attempt_upper_bound": inverse_attempts,
            "route": "restricted-seeds-direct-root-lifting"}


def main():
    if not __debug__:
        raise RuntimeError("Verification requires assertions; run Python without -O")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-all-actions", action="store_true",
                        help="Compare support and full-permutation orbits for every class")
    arguments = parser.parse_args()
    small_domains = check_small_domains()
    local = [classify_local(length) for length in EXPECTED_LOCAL]
    rows = []
    for row, length_a, length_b in ROWS:
        families = []
        for exponent_a in EXPECTED_LOCAL[length_a]:
            for exponent_b in EXPECTED_LOCAL[length_b]:
                if length_a == length_b and exponent_a > exponent_b:
                    continue
                families.append(count_family(length_a, length_b, exponent_a, exponent_b,
                                             arguments.check_all_actions))
        counts = {key: sum(family[key] for family in families)
                  for key in ("raw_candidates", "candidate_orbits",
                              "raw_valid_pairs", "valid_pair_classes")}
        assert (counts["candidate_orbits"], counts["valid_pair_classes"]) == EXPECTED_COUNTS[row]
        rows.append({"row": row, "lengths": [length_a, length_b],
                     **counts, "families": families})
    report = {"format": "td18-two-cycle-no-qualifying-interleavings-v2",
              "scope": "Active restricted Table 2 seed counts and root-attempt bound; no sorting certificates",
              "small_domain_checks": small_domains,
              "all_actions_checked": arguments.check_all_actions,
              "local_classification": local, "rows": rows,
              "literature_example": check_literature_example(),
              "workload": check_table_and_workload()}
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
