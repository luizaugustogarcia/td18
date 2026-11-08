#!/usr/bin/env python3
"""Run the independent joint-reduction root counter over every partition.

Generated reports and representatives belong in an ignored data directory.
Each native job writes temporary files; only a successful, validated result is
renamed into place. The final report requires every independently regenerated
partition exactly once. This is a counting audit, not a sorting certificate.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import subprocess
import time


def partitions(n: int, maximum: int | None = None, prefix: tuple[int, ...] = ()):
    if n == 0:
        if sum(x % 2 == 0 for x in prefix) % 2 == 0:
            yield prefix
        return
    for length in range(min(n, maximum or n), 1, -1):
        yield from partitions(n - length, length, prefix + (length,))


def validate_representatives(path: Path, domain: int, expected: int) -> None:
    with path.open() as stream:
        if stream.readline().rstrip('\n') != f'TD18-JOINT-RESIDUALS 1 N={domain} scope=oriented':
            raise ValueError(f'Invalid representative header: {path}')
        count = 0
        for line in stream:
            if line.startswith('COUNT '):
                if line.rstrip('\n') != f'COUNT {expected}' or count != expected or stream.read():
                    raise ValueError(f'Incorrect or nonterminal representative count: {path}')
                return
            values = line.split()
            if len(values) != domain:
                raise ValueError(f'Malformed representative at row {count + 1}: {path}')
            count += 1
    raise ValueError(f'Missing representative completion line: {path}')


def run_partition(binary: Path, output: Path, part: tuple[int, ...], representatives: bool) -> dict:
    key = '-'.join(map(str, part))
    report = output / f'partition-{key}.json'
    raw = output / f'partition-{key}.json.tmp'
    errors = output / f'partition-{key}.stderr.tmp'
    reps = output / f'partition-{key}.txt'
    reps_temp = output / f'partition-{key}.txt.tmp'
    arguments = [str(binary), '--partition=' + ','.join(map(str, part))]
    if representatives:
        arguments.append('--representatives=' + str(reps_temp))
    with raw.open('w') as stdout, errors.open('w') as stderr:
        subprocess.run(arguments, stdout=stdout, stderr=stderr, check=True)
    row = json.loads(raw.read_text())
    if row.get('format') != 'td18-joint-residual-count-v1' or row.get('partition') != list(part):
        raise ValueError(f'Unexpected native report for {part}')
    for field in ('oriented_root_classes', 'no_qualifying_first_move',
                  'qualifying_but_no_favorable_pair', 'prefix_nodes',
                  'realizable_leaves', 'ignored_unoriented_leaves'):
        if not isinstance(row.get(field), int) or row[field] < 0:
            raise ValueError(f'Invalid {field} for {part}')
    if row['oriented_root_classes'] != row['no_qualifying_first_move'] + row['qualifying_but_no_favorable_pair']:
        raise ValueError(f'Inconsistent class split for {part}')
    if representatives:
        validate_representatives(reps_temp, sum(part), row['oriented_root_classes'])
        reps_temp.replace(reps)
        row['representatives'] = reps.name
    raw.write_text(json.dumps(row, sort_keys=True, indent=2) + '\n')
    raw.replace(report)
    if errors.stat().st_size == 0:
        errors.unlink()
    else:
        errors.replace(output / f'partition-{key}.stderr')
    return row


def merge_representatives(output: Path, domain: int, rows: list[dict]) -> str:
    result = output / 'representatives.txt'
    temporary = output / 'representatives.txt.tmp'
    count = 0
    with temporary.open('w') as target:
        target.write(f'TD18-JOINT-RESIDUALS 1 N={domain} scope=oriented\n')
        for row in rows:
            source = output / row['representatives']
            with source.open() as stream:
                stream.readline()
                for line in stream:
                    if line.startswith('COUNT '):
                        break
                    target.write(line)
                    count += 1
        expected = sum(row['oriented_root_classes'] for row in rows)
        if count != expected:
            raise ValueError('Merged representative count is incomplete')
        target.write(f'COUNT {count}\n')
    temporary.replace(result)
    return result.name


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--n', type=int, default=19)
    available_cpus = len(os.sched_getaffinity(0)) if hasattr(os, 'sched_getaffinity') else (os.cpu_count() or 1)
    parser.add_argument('--workers', type=int, default=available_cpus,
                        help='Maximum parallel partition jobs (default: all available CPU cores)')
    parser.add_argument('--binary', type=Path, default=Path('/tmp/td18-joint-count'))
    parser.add_argument('--output', type=Path, default=Path('data/joint-reduction-audit/native-v1'))
    parser.add_argument('--representatives', action='store_true')
    parser.add_argument('--merge-representatives', action='store_true')
    options = parser.parse_args()
    if not 3 <= options.n <= 19 or options.workers < 1:
        parser.error('Expected domain 3..19 and positive worker count')
    if options.merge_representatives and not options.representatives:
        parser.error('--merge-representatives requires --representatives')
    inventory = list(partitions(options.n))
    if options.n == 19 and len(inventory) != 53:
        raise ValueError('The independent 19-symbol inventory must contain 53 partitions')
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if list(output.iterdir()):
        raise ValueError(f'Refusing to mix a new run with existing output: {output}')
    binary = options.binary.resolve()
    started = time.monotonic()
    results = {}
    with ThreadPoolExecutor(max_workers=options.workers) as pool:
        jobs = {pool.submit(run_partition, binary, output, part, options.representatives): part
                for part in inventory}
        for job in as_completed(jobs):
            part = jobs[job]
            row = job.result()
            results[part] = row
            print(json.dumps({'completed': len(results), 'required': len(inventory),
                              'partition': part, 'classes': row['oriented_root_classes'],
                              'elapsed_seconds': round(time.monotonic() - started, 3)}), flush=True)
    if set(results) != set(inventory):
        raise ValueError('Incomplete partition inventory')
    rows = [results[part] for part in inventory]
    report = {'format': 'td18-joint-count-run-v1', 'complete': True,
              'domain': options.n, 'required_partitions': len(inventory),
              'completed_partitions': len(rows),
              'worker_limit': options.workers, 'available_cpu_cores': available_cpus,
              'scope': 'oriented roots with no first gain >=2 and no qualifying first gain 1 followed by gain >=2',
              'totally_unoriented_roots': 'excluded; separate direct obligations',
              'oriented_root_classes': sum(row['oriented_root_classes'] for row in rows),
              'no_qualifying_first_move': sum(row['no_qualifying_first_move'] for row in rows),
              'qualifying_but_no_favorable_pair': sum(row['qualifying_but_no_favorable_pair'] for row in rows),
              'prefix_nodes': sum(row['prefix_nodes'] for row in rows),
              'rows': rows}
    if options.merge_representatives:
        report['representatives'] = merge_representatives(output, options.n, rows)
    report['elapsed_seconds'] = round(time.monotonic() - started, 3)
    temporary = output / 'counts.json.tmp'
    temporary.write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')
    temporary.replace(output / 'counts.json')
    print(json.dumps({key: value for key, value in report.items() if key != 'rows'}, sort_keys=True), flush=True)


if __name__ == '__main__':
    main()
