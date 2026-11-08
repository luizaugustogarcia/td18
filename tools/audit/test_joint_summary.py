"""Regression checks for complete, paper-backed joint counting reports."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).with_name('summarize_joint_counts.py')
SPEC = importlib.util.spec_from_file_location('joint_summary', MODULE)
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)
PAPER = MODULE.parents[2] / 'docs/td18-reduction-count.tex'


class SummaryCompletenessTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.rows = summary.paper_rows(PAPER)
        self.counts = []
        self.clearings = []
        for _, partition, _, joint, cleared, remaining in self.rows:
            self.counts.append({
                'format': 'td18-joint-residual-count-v1',
                'partition': list(partition),
                'oriented_root_classes': joint,
                'no_qualifying_first_move': 0,
                'qualifying_but_no_favorable_pair': joint,
                'prefix_nodes': joint,
                'realizable_leaves': joint,
                'ignored_unoriented_leaves': 0,
            })
            self.clearings.append({
                'format': 'td18-joint-support-clearing-count-v1',
                'domain': 19,
                'input': 'partition-' + '-'.join(map(str, partition)) + '.txt',
                'scanned': joint,
                'support_clearing': cleared,
                'remaining': remaining,
                'least_closing_length': {'7': cleared, '9': 0, '11': 0, '13': 0},
            })

    def write_records(self, name, records):
        path = self.folder / name
        path.write_text(''.join(json.dumps(record) + '\n' for record in records))
        return path

    def summarize(self, counts=None, clearings=None, paper=PAPER):
        records = self.write_records('counts.jsonl', self.counts if counts is None else counts)
        closures = self.write_records('clearings.jsonl', self.clearings if clearings is None else clearings)
        return summary.summarize([records], paper, closures)

    def test_complete_paper_inventory_and_totals(self):
        result = self.summarize()
        self.assertEqual(53, result['partitions_completed'])
        self.assertEqual(104, result['sixteen_symbol_endpoint_types_checked'])
        self.assertEqual(248082611, result['direct_root_class_obligations'])
        self.assertFalse(result['sorting_certificates_checked'])

    def test_missing_and_duplicate_native_partitions_reject(self):
        for rows in [self.counts[:-1], self.counts + [self.counts[0]]]:
            with self.subTest(size=len(rows)), self.assertRaises(ValueError):
                self.summarize(counts=rows)

    def test_consistent_native_count_disagreeing_with_paper_rejects(self):
        self.counts[0]['oriented_root_classes'] += 1
        self.counts[0]['qualifying_but_no_favorable_pair'] += 1
        self.counts[0]['realizable_leaves'] += 1
        with self.assertRaisesRegex(ValueError, 'source row'):
            self.summarize()

    def test_missing_and_duplicate_clearing_partitions_reject(self):
        for rows in [self.clearings[:-1], self.clearings + [self.clearings[0]]]:
            with self.subTest(size=len(rows)), self.assertRaises(ValueError):
                self.summarize(clearings=rows)

    def test_consistent_clearing_count_disagreeing_with_paper_rejects(self):
        row = next(row for row in self.clearings if row['support_clearing'])
        row['support_clearing'] -= 1
        row['least_closing_length']['7'] -= 1
        row['remaining'] += 1
        with self.assertRaisesRegex(ValueError, 'source row'):
            self.summarize()

    def test_missing_duplicate_and_malformed_paper_rows_reject(self):
        source = PAPER.read_text()
        first = next(line for line in source.splitlines() if line.startswith('\\jointrow'))
        for content in [source.replace(first + '\n', ''),
                        source.replace(first, first + '\n' + first),
                        source.replace(first, first + ' trailing')]:
            paper = self.folder / 'paper.tex'
            paper.write_text(content)
            with self.subTest(content=content[:20]), self.assertRaises(ValueError):
                self.summarize(paper=paper)


if __name__ == '__main__':
    unittest.main()
