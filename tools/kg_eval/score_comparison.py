"""Score three frozen B1/B2/B3 runs without treating repeats as new cases."""

import argparse
import json
import math
import re
import statistics
from collections import Counter
from pathlib import Path

ALGORITHMS = ("b1", "b2", "b3")
MANIFEST_KEYS = ("caseHash", "corpusHash", "graphHash", "chunkHash", "model",
                 "temperature", "embeddingModel", "gitRevision")
REFERENCE_PATTERNS = ((re.compile(r"r#(\d+)"), "relationId"),
                      (re.compile(r"c#(\d+)"), "chunkId"),
                      (re.compile(r"(?<![\w#])#(\d+)"), None))


def load_run(directory: Path, expected_count: int = 100) -> dict:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    batches = sorted(directory.glob("batch*.json"), key=lambda p: int(p.stem[5:]))
    if len(batches) != 10:
        raise ValueError(f"{directory}: expected 10 batches, found {len(batches)}")
    rows = []
    for path in batches:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            raise ValueError(f"{path}: batch must be a JSON array")
        rows.extend(data)
    ids = [row.get("id") for row in rows]
    if len(rows) != expected_count or len(set(ids)) != expected_count or None in ids:
        raise ValueError(f"{directory}: expected {expected_count} distinct question IDs")
    for row in rows:
        for algorithm in ALGORITHMS:
            for suffix in ("answer", "citations", "ms", "promptTokens",
                           "completionTokens", "pass", "judgeNote", "legacyPass"):
                if algorithm + "_" + suffix not in row:
                    raise ValueError(f"{directory}/{row['id']}: missing {algorithm}_{suffix}")
            if not isinstance(row[algorithm + "_pass"], bool):
                raise ValueError(f"{directory}/{row['id']}: pass must be boolean")
            if not isinstance(row[algorithm + "_citations"], list):
                raise ValueError(f"{directory}/{row['id']}: citations must be an array")
        if not row.get("type") or not row.get("question"):
            raise ValueError(f"{directory}/{row['id']}: missing type or question")
    return {"directory": str(directory), "manifest": manifest,
            "rows": {row["id"]: row for row in rows}}


def validate_shared_runs(runs: list[dict]) -> None:
    if len(runs) != 3:
        raise ValueError("exactly three independent runs are required")
    first = runs[0]
    for run in runs[1:]:
        if set(run["rows"]) != set(first["rows"]):
            raise ValueError("question IDs differ between runs")
        for key in MANIFEST_KEYS:
            if run["manifest"].get(key) != first["manifest"].get(key):
                raise ValueError(f"{key} differs between runs")
        for case_id, row in run["rows"].items():
            original = first["rows"][case_id]
            if (row["question"], row["type"]) != (original["question"], original["type"]):
                raise ValueError(f"{case_id}: question or type differs between runs")


# Mirror CitationText: only an adjacent color label masks a hex value. A nearby citation stays visible.
COLOR_HEX = re.compile(r"(?<![\w#])#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})(?![0-9a-z])", re.I)
COLOR_PREFIX = re.compile(r'(?:颜色|色值|底色|文字色|背景色|配色|强调色|color|background)(?:为|是|：|:|=|\s|`|"|“){0,8}$', re.I)
COLOR_SUFFIX = re.compile(r'^[`"”\s]*(?:底色|背景色|文字色|色值|颜色|底、|文字、)', re.I)


def citation_text(answer: str) -> str:
    masked = list(answer)
    for match in COLOR_HEX.finditer(answer):
        if (COLOR_PREFIX.search(answer[max(0, match.start() - 24):match.start()])
                or COLOR_SUFFIX.search(answer[match.end():match.end() + 12])):
            masked[match.start():match.end()] = " " * (match.end() - match.start())
    return "".join(masked)


def invalid_references(row: dict, algorithm: str) -> list[str]:
    citations = row[algorithm + "_citations"]
    answer = citation_text(row[algorithm + "_answer"] or "")
    allowed = {key: {str(c[key]) for c in citations if key in c}
               for key in ("entityId", "relationId", "chunkId")}
    violations = []
    for pattern, kind in REFERENCE_PATTERNS:
        field = kind or ("chunkId" if algorithm == "b1" else "entityId")
        for match in pattern.finditer(answer):
            if match.group(1) not in allowed[field]:
                violations.append(match.group(0))
    return sorted(set(violations))


def exact_mcnemar(b3_only: int, baseline_only: int) -> float:
    discordant = b3_only + baseline_only
    if discordant == 0:
        return 1.0
    tail = sum(math.comb(discordant, k) for k in range(min(b3_only, baseline_only) + 1))
    return min(1.0, 2.0 * tail / (2 ** discordant))


def score_run(run: dict) -> dict:
    rows = list(run["rows"].values())
    per_algorithm = {}
    audit = []
    for algorithm in ALGORITHMS:
        by_type = {}
        for case_type in sorted({row["type"] for row in rows}):
            selected = [row for row in rows if row["type"] == case_type]
            by_type[case_type] = {"correct": sum(row[algorithm + "_pass"] for row in selected),
                                  "total": len(selected)}
        primary = [row for row in rows if row["type"] in ("E", "T", "M")]
        latency = [float(row[algorithm + "_ms"]) for row in rows]
        violations = {row["id"]: invalid_references(row, algorithm) for row in rows}
        violations = {key: value for key, value in violations.items() if value}
        per_algorithm[algorithm] = {
            "byType": by_type,
            "overallCorrect": sum(row[algorithm + "_pass"] for row in rows),
            "overallTotal": len(rows),
            "primaryCorrect": sum(row[algorithm + "_pass"] for row in primary),
            "primaryTotal": len(primary),
            "legacyCorrect": sum(row[algorithm + "_legacyPass"] for row in rows),
            "latencyMeanMs": statistics.mean(latency),
            "latencyMedianMs": statistics.median(latency),
            "promptTokens": sum(row[algorithm + "_promptTokens"] for row in rows),
            "completionTokens": sum(row[algorithm + "_completionTokens"] for row in rows),
            "citationViolations": violations,
        }
        for row in rows:
            # Review every B3 answer, including failures that might contain unsupported facts.
            if algorithm == "b3" or row[algorithm + "_pass"] or row["id"] in violations:
                audit.append({"id": row["id"], "algorithm": algorithm,
                              "type": row["type"], "question": row["question"],
                              "answer": row[algorithm + "_answer"],
                              "citations": row[algorithm + "_citations"],
                              "autoPass": row[algorithm + "_pass"],
                              "judgeNote": row[algorithm + "_judgeNote"],
                              "invalidReferences": violations.get(row["id"], [])})
    pairs = {}
    for baseline in ("b1", "b2"):
        b3_only = sum(row["b3_pass"] and not row[baseline + "_pass"] for row in rows)
        base_only = sum(row[baseline + "_pass"] and not row["b3_pass"] for row in rows)
        pairs["b3_vs_" + baseline] = {"total": len(rows),
                                      "b3Only": b3_only, "baselineOnly": base_only,
                                      "mcnemarExactP": exact_mcnemar(b3_only, base_only)}
    actions = Counter(action for row in rows for action in row.get("b3_actions", []))
    return {"directory": run["directory"], "algorithms": per_algorithm,
            "pairs": pairs, "b3ToolCoverage": dict(actions),
            "auditRows": audit}


def build_report(runs: list[dict]) -> tuple[dict, str]:
    validate_shared_runs(runs)
    scored = [score_run(run) for run in runs]
    stable = all(item["algorithms"]["b3"]["overallCorrect"]
                 > max(item["algorithms"]["b1"]["overallCorrect"],
                       item["algorithms"]["b2"]["overallCorrect"])
                 and not item["algorithms"]["b3"]["citationViolations"]
                 for item in scored)
    report = {"manifest": {k: runs[0]["manifest"].get(k) for k in MANIFEST_KEYS},
              "runs": scored, "b3StableAutoGate": stable,
              "humanAuditRequired": True}
    case_count = len(runs[0]["rows"])
    lines = ["# B1 / B2 / B3 同条件评测", "",
             "自动判分不能替代事实人工复核；三次重复运行按同一套题分别报告。", "",
             f"| 运行 | B1 全部 {case_count} 题 | B2 全部 {case_count} 题 | B3 全部 {case_count} 题 | B3 无效引用 |",
             "|---|---:|---:|---:|---:|"]
    for index, item in enumerate(scored, 1):
        algorithms = item["algorithms"]
        lines.append(f"| {index} | " + " | ".join(
            f"{algorithms[name]['overallCorrect']}/{algorithms[name]['overallTotal']}"
            for name in ALGORITHMS) +
            f" | {len(algorithms['b3']['citationViolations'])} |")
    lines += ["", "| 运行 | 算法 | E | T | M | U | E+T+M |",
              "|---|---|---:|---:|---:|---:|---:|"]
    for index, item in enumerate(scored, 1):
        for name in ALGORITHMS:
            metrics = item["algorithms"][name]
            by_type = metrics["byType"]
            breakdown = [f"{by_type[kind]['correct']}/{by_type[kind]['total']}"
                         if kind in by_type else "0/0" for kind in ("E", "T", "M", "U")]
            lines.append(f"| {index} | {name.upper()} | " + " | ".join(breakdown) +
                         f" | {metrics['primaryCorrect']}/{metrics['primaryTotal']} |")
    lines += ["", f"B3 自动门槛：{'通过' if stable else '未通过'}。要求每轮全部题目正确数均高于 B1、B2，且 B3 无无效引用 ID。", "",
              "无效引用 ID 为零不代表内容没有编造；必须逐条人工核查 B3 答案的事实与引文，才能作最终验收。", ""]
    for index, item in enumerate(scored, 1):
        lines += [f"## 第 {index} 次运行", "", f"原始答案：`{item['directory']}`", ""]
        for pair, counts in item["pairs"].items():
            lines.append(f"- {pair}: B3 单独正确 {counts['b3Only']}，基线单独正确 "
                         f"{counts['baselineOnly']}，精确 McNemar p={counts['mcnemarExactP']:.4g}")
        lines += ["", "人工复核清单见 report.json 的 auditRows；应核查命中答案的实体、关系、日期和来源。", ""]
    return report, "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", nargs=3, type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    report, markdown = build_report([load_run(path) for path in args.runs])
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.out_dir / "report.md").write_text(markdown, encoding="utf-8")
    print(args.out_dir / "report.md")


if __name__ == "__main__":
    main()
