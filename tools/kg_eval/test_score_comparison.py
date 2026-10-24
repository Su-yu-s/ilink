import json
import tempfile
import unittest
from pathlib import Path

from score_comparison import build_report, load_run, invalid_references


def result(case_id, case_type, b1, b2, b3, invented=False):
    row = {"id": case_id, "type": case_type, "question": f"问题 {case_id}"}
    for name, correct in (("b1", b1), ("b2", b2), ("b3", b3)):
        row.update({f"{name}_answer": "事实 #999" if name == "b3" and invented else "事实 #1",
                    f"{name}_citations": [{"entityId": 1, "chunkId": 1}],
                    f"{name}_ms": 10, f"{name}_promptTokens": 2,
                    f"{name}_completionTokens": 3, f"{name}_pass": correct,
                    f"{name}_judgeNote": "全部要点命中" if correct else "要点未命中",
                    f"{name}_legacyPass": correct})
    row["b3_actions"] = ["search_entities", "query_temporal"]
    return row


class ComparisonScorerTest(unittest.TestCase):
    def test_color_does_not_hide_nearby_invalid_citation(self):
        row = result("E1", "E", True, True, True)
        row["b3_answer"] = "文字色为 #171717，来源 #999。c#1"
        self.assertEqual(["#999"], invalid_references(row, "b3"))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.cases = [result("E1", "E", True, False, True),
                      result("T1", "T", False, True, True),
                      result("M1", "M", False, False, True, invented=True),
                      result("U1", "U", True, True, False)]

    def tearDown(self):
        self.temp.cleanup()

    def make_run(self, index, rows=None, graph="graph"):
        path = self.root / f"run{index}"
        path.mkdir()
        manifest = {"caseHash": "cases", "corpusHash": "corpus", "graphHash": graph,
                    "chunkHash": "chunks", "model": "model", "temperature": 0.3,
                    "embeddingModel": "embed", "gitRevision": "revision"}
        (path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        rows = self.cases if rows is None else rows
        for batch in range(1, 11):
            (path / f"batch{batch}.json").write_text(
                json.dumps(rows if batch == 1 else [], ensure_ascii=False), encoding="utf-8")
        return load_run(path, expected_count=4)

    def test_scores_pairs_and_detects_invented_reference(self):
        runs = [self.make_run(index) for index in range(3)]
        report, markdown = build_report(runs)
        first = report["runs"][0]
        self.assertEqual(3, first["algorithms"]["b3"]["overallCorrect"])
        self.assertEqual(4, first["algorithms"]["b3"]["overallTotal"])
        self.assertEqual(3, first["algorithms"]["b3"]["primaryCorrect"])
        self.assertEqual(1, first["algorithms"]["b1"]["primaryCorrect"])
        self.assertEqual(4, first["pairs"]["b3_vs_b1"]["total"])
        self.assertEqual(2, first["pairs"]["b3_vs_b1"]["b3Only"])
        self.assertEqual(2, first["pairs"]["b3_vs_b2"]["b3Only"])
        self.assertEqual(["#999"], first["algorithms"]["b3"]["citationViolations"]["M1"])
        self.assertFalse(report["b3StableAutoGate"])
        self.assertTrue(report["humanAuditRequired"])
        self.assertEqual(4, len([row for row in first["auditRows"]
                                 if row["algorithm"] == "b3"]))
        self.assertEqual(3, len(report["runs"]))
        self.assertIn("三次重复运行按同一套题分别报告", markdown)
        self.assertIn("B3 全部 4 题", markdown)
        self.assertIn("| 运行 | 算法 | E | T | M | U | E+T+M |", markdown)
        self.assertIn("无效引用 ID 为零不代表内容没有编造", markdown)

    def test_primary_win_does_not_hide_unanswerable_regression(self):
        rows = [result("E1", "E", False, False, True),
                result("T1", "T", False, False, True),
                result("M1", "M", True, False, False),
                result("U1", "U", True, False, False)]
        report, markdown = build_report([self.make_run(i, rows=rows) for i in range(3)])
        first = report["runs"][0]
        self.assertEqual(2, first["algorithms"]["b3"]["primaryCorrect"])
        self.assertEqual(1, first["algorithms"]["b1"]["primaryCorrect"])
        self.assertEqual(2, first["algorithms"]["b3"]["overallCorrect"])
        self.assertEqual(2, first["algorithms"]["b1"]["overallCorrect"])
        self.assertEqual({"correct": 0, "total": 1},
                         first["algorithms"]["b3"]["byType"]["U"])
        self.assertEqual(2, first["pairs"]["b3_vs_b1"]["baselineOnly"])
        self.assertFalse(report["b3StableAutoGate"])
        self.assertIn("| 1 | B3 | 1/1 | 1/1 | 0/1 | 0/1 | 2/3 |", markdown)
        self.assertIn("B3 自动门槛：未通过", markdown)
        self.assertIn({"id": "U1", "algorithm": "b3", "type": "U",
                       "question": "问题 U1", "answer": "事实 #1",
                       "citations": [{"entityId": 1, "chunkId": 1}],
                       "autoPass": False, "judgeNote": "要点未命中",
                       "invalidReferences": []}, first["auditRows"])

    def test_gate_requires_all_three_runs_to_beat_both_baselines(self):
        winning = [result("E1", "E", False, False, True),
                   result("T1", "T", False, False, True),
                   result("M1", "M", False, False, True),
                   result("U1", "U", True, True, True)]
        tied = [result("E1", "E", False, False, True),
                result("T1", "T", True, False, True),
                result("M1", "M", True, False, False),
                result("U1", "U", True, True, True)]
        report, _ = build_report([self.make_run(1, rows=winning),
                                  self.make_run(2, rows=winning),
                                  self.make_run(3, rows=tied)])
        self.assertFalse(report["b3StableAutoGate"])
        self.assertEqual(2, report["runs"][2]["algorithms"]["b3"]["primaryCorrect"])
        self.assertEqual(3, report["runs"][2]["algorithms"]["b3"]["overallCorrect"])
        self.assertEqual(3, report["runs"][2]["algorithms"]["b1"]["overallCorrect"])

    def test_rejects_snapshot_drift(self):
        runs = [self.make_run(1), self.make_run(2), self.make_run(3, graph="different")]
        with self.assertRaisesRegex(ValueError, "graphHash"):
            build_report(runs)

    def test_rejects_duplicate_question_ids(self):
        rows = self.cases[:3] + [self.cases[0]]
        with self.assertRaisesRegex(ValueError, "distinct question IDs"):
            self.make_run(1, rows=rows)


if __name__ == "__main__":
    unittest.main()
