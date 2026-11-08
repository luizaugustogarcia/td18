#!/usr/bin/env python3
"""Check complete partition coverage and summarize the joint-selection audit.

Count reports are generated artifacts. This does not verify sorting witnesses.
"""
import argparse
import json
from collections import Counter
from itertools import product
from pathlib import Path
import re
import subprocess


def partitions(total, maximum=None):
    if total == 0:
        yield ()
        return
    for length in range(min(total, maximum or total), 1, -1):
        for tail in partitions(total - length, length):
            yield (length,) + tail


def admissible(total):
    return {p for p in partitions(total) if sum(k % 2 == 0 for k in p) % 2 == 0}


def paper_rows(path):
    """Read the complete deterministic root inventory from the source paper."""
    pattern = re.compile(r'\\jointrow\{(\d+)\}\{([\d,]+)\}'
                         r'\{(\d+)\}\{(\d+)\}\{(\d+)\}\{(\d+)\}')
    rows = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line.startswith(r'\jointrow'):
            continue
        match = pattern.fullmatch(line)
        if match is None:
            raise ValueError('Malformed source root inventory row')
        number = int(match[1])
        partition = tuple(map(int, match[2].split(',')))
        u, j, c, o = map(int, match.group(3, 4, 5, 6))
        if c > j or o != j - c:
            raise ValueError(f'Inconsistent source support-clearing counts at row {number}')
        rows.append((number, partition, u, j, c, o))
    expected = sorted(admissible(19), reverse=True)
    if len(expected) != 53 or [row[0] for row in rows] != list(range(1, 54)):
        raise ValueError('Incomplete or reordered source root inventory')
    if [row[1] for row in rows] != expected:
        raise ValueError('Source partitions differ from independent enumeration')
    return rows


def check_sixteen_inventory():
    """Independently enumerate the complete endpoint type cover."""
    expected = set()
    for partition in admissible(16):
        if sum(k // 2 for k in partition) > 7:
            continue
        groups = sorted(Counter(partition).items(), reverse=True)
        for oriented_counts in product(*(range(c + 1) if k >= 3 else range(1)
                                         for k, c in groups)):
            expected.add(tuple((k, oriented)
                               for (k, c), a in zip(groups, oriented_counts)
                               for oriented in [False] * (c - a) + [True] * a))
    elementary = sum(any(k == 3 and oriented for k, oriented in row) for row in expected)
    if len(expected) != 104 or elementary != 52:
        raise ValueError('Incorrect 16-symbol endpoint inventory')
    return len(expected)


def count_records(path):
    source = path.read_text()
    try:
        value = json.loads(source)
    except json.JSONDecodeError:
        return [json.loads(line) for line in source.splitlines() if line.strip()]
    if value.get('format') == 'td18-joint-count-run-v1':
        if value.get('complete') is not True or value.get('domain') != 19:
            raise ValueError('Expected a completed 19-symbol run')
        rows = value['rows']
        if value.get('required_partitions') != 53 or value.get('completed_partitions') != len(rows):
            raise ValueError('Incorrect completed-run partition inventory')
        for field in ('oriented_root_classes', 'no_qualifying_first_move',
                      'qualifying_but_no_favorable_pair', 'prefix_nodes'):
            if value[field] != sum(row[field] for row in rows):
                raise ValueError(f'Incorrect completed-run total: {field}')
        return rows
    return [value]


def clearing_records(path, expected, native_records):
    records = {}
    for line in path.read_text().splitlines():
        record = json.loads(line)
        if record.get('format') != 'td18-joint-support-clearing-count-v1' or record.get('domain') != 19:
            raise ValueError('Unsupported support-clearing report')
        match = re.fullmatch(r'partition-([0-9-]+)\.txt', Path(record['input']).name)
        if match is None:
            raise ValueError('Support-clearing record lacks a deterministic partition key')
        partition = tuple(map(int, match[1].split('-')))
        if partition not in expected or partition in records:
            raise ValueError('Unexpected or duplicate support-clearing partition')
        for field in ('scanned', 'support_clearing', 'remaining'):
            if type(record[field]) is not int or record[field] < 0:
                raise ValueError('Invalid support-clearing count')
        by_length = record['least_closing_length']
        if set(by_length) != {'7', '9', '11', '13'} or any(type(v) is not int or v < 0 for v in by_length.values()):
            raise ValueError('Invalid support-clearing length inventory')
        if record['scanned'] != native_records[partition]['oriented_root_classes']:
            raise ValueError('Support-clearing scan does not cover the native class count')
        if record['support_clearing'] + record['remaining'] != record['scanned'] or sum(by_length.values()) != record['support_clearing']:
            raise ValueError('Inconsistent support-clearing totals')
        records[partition] = record
    if set(records) != expected:
        raise ValueError('Missing support-clearing partitions')
    return records


def summarize(paths, paper, clearings=None):
    expected = admissible(19)
    records = {}
    for path in paths:
        for record in count_records(path):
            if record.get('format') != 'td18-joint-residual-count-v1':
                raise ValueError(f'Unsupported count record in {path}')
            partition = tuple(record['partition'])
            if partition not in expected or partition in records:
                raise ValueError(f'Unexpected or repeated partition {partition}')
            for field in ('oriented_root_classes', 'no_qualifying_first_move',
                          'qualifying_but_no_favorable_pair', 'prefix_nodes',
                          'realizable_leaves', 'ignored_unoriented_leaves'):
                value = record[field]
                if type(value) is not int or value < 0:
                    raise ValueError(f'Invalid {field} at {partition}')
            if record['no_qualifying_first_move'] + record['qualifying_but_no_favorable_pair'] != record['oriented_root_classes']:
                raise ValueError(f'Inconsistent residual split at {partition}')
            records[partition] = record
    if set(records) != expected:
        raise ValueError(f'Missing partitions: {sorted(expected - records.keys(), reverse=True)}')
    rows = paper_rows(paper)
    unoriented = {partition: u for _, partition, u, _, _, _ in rows}
    for number, partition, _, j, _, _ in rows:
        if records[partition]['oriented_root_classes'] != j:
            raise ValueError(f'Native residual count differs from source row {number}')
    sixteen = check_sixteen_inventory()
    ordered = [records[p] for p in sorted(expected, reverse=True)]
    oriented_count = sum(row['oriented_root_classes'] for row in ordered)
    unoriented_count = sum(unoriented.values())
    clearing = clearing_records(clearings, expected, records) if clearings else None
    closed = sum(row['support_clearing'] for row in clearing.values()) if clearing is not None else None
    if clearing is not None:
        for number, partition, _, _, c, o in rows:
            if (clearing[partition]['support_clearing'], clearing[partition]['remaining']) != (c, o):
                raise ValueError(f'Support-clearing count differs from source row {number}')
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], check=True,
                              text=True, capture_output=True).stdout.strip()
    dirty = bool(subprocess.run(['git', 'status', '--porcelain'], check=True,
                                text=True, capture_output=True).stdout)
    result = {
        'format': 'td18-joint-selection-summary-v2',
        'code_revision': revision,
        'worktree_dirty': dirty,
        'domain': 19,
        'scope': 'root classes after favorable joint-prefix routing; no sorting search',
        'root_budget': 10,
        'partitions_required': len(expected),
        'partitions_completed': len(ordered),
        'nonempty_oriented_residual_partitions': sum(row['oriented_root_classes'] > 0 for row in ordered),
        'totally_unoriented_root_classes': unoriented_count,
        'oriented_residual_root_classes': oriented_count,
        'root_classes_before_support_clearing': oriented_count + unoriented_count,
        'support_clearing_audited': clearing is not None,
        'known_support_clearing_root_classes': closed,
        'oriented_root_classes_after_support_clearing': oriented_count - closed if closed is not None else None,
        'direct_root_class_obligations': oriented_count + unoriented_count - closed if closed is not None else None,
        'oriented_roots_without_qualifying_first_move': sum(row['no_qualifying_first_move'] for row in ordered),
        'oriented_roots_with_qualifying_moves_but_no_favorable_pair': sum(row['qualifying_but_no_favorable_pair'] for row in ordered),
        'sixteen_symbol_endpoint_types_checked': sixteen,
        'sorting_obstructions_counted': False,
        'final_avoidance_root_count': None,
        'sorting_certificates_checked': False,
        'partitions': [{**row, 'totally_unoriented_root_classes': unoriented[tuple(row['partition'])]}
                       for row in ordered],
    }
    if clearing is not None:
        for row in result['partitions']:
            closure = clearing[tuple(row['partition'])]
            row['support_clearing_root_classes'] = closure['support_clearing']
            row['oriented_root_classes_after_support_clearing'] = closure['remaining']
        result['support_clearing_by_least_length'] = {k: sum(row['least_closing_length'][k] for row in clearing.values())
                                                     for k in ('7', '9', '11', '13')}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reports', nargs='+', type=Path)
    parser.add_argument('--paper', type=Path, default=Path('docs/td18-reduction-count.tex'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--clearings', type=Path, help='Complete support-clearing post-count JSONL report')
    args = parser.parse_args()
    result = summarize(args.reports, args.paper, args.clearings)
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        with args.output.open('x') as destination:
            destination.write(text)
    else:
        print(text, end='')


if __name__ == '__main__':
    main()
