"""Markdown report generation for the evaluation harness.

Reads whatever suite results exist under ``docs/eval/`` (a retrieval-only run
refreshes the retrieval section and keeps the committed agent section, and
vice versa) and writes ``docs/eval/report.md`` with a summary table plus
per-question tables and failures.
"""
import json
import time
from pathlib import Path
from typing import Optional

from eval.dataset import REPO_ROOT, load_config, load_questions

DOCS_DIR = REPO_ROOT / "docs" / "eval"
REPORT_FILE = DOCS_DIR / "report.md"
RETRIEVAL_FILE = DOCS_DIR / "retrieval_results.json"
AGENT_FILE = DOCS_DIR / "agent_results.json"


def _load(path: Path) -> Optional[dict]:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return None
    return None


def _fmt(value, digits: int = 4) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def write_report() -> Path:
    config = load_config()
    questions_meta = load_questions().get("metadata", {})
    retrieval = _load(RETRIEVAL_FILE)
    agent = _load(AGENT_FILE)

    lines = ["# Codebase-AI Evaluation Report", ""]
    lines.append(f"**Generated:** {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"**Dataset:** eval/questions.json "
                 f"v{questions_meta.get('version', '?')} "
                 f"({len(load_questions().get('questions', []))} questions)")
    for name, spec in config["repos"].items():
        lines.append(f"- `{name}` pinned at `{spec['sha']}`")
    lines += ["", "## Summary", "", "| Metric | Value |", "|---|---|"]

    r = (retrieval or {}).get("aggregate", {})
    a = (agent or {}).get("aggregate", {})
    summary = [
        ("precision@5", r.get("precision@5")),
        ("precision@10", r.get("precision@10")),
        ("recall@5", r.get("recall@5")),
        ("recall@10", r.get("recall@10")),
        ("MRR", r.get("mrr")),
        ("hit-rate@5", r.get("hit_rate@5")),
        ("hit-rate@10", r.get("hit_rate@10")),
        ("citation accuracy", a.get("citation_accuracy")),
        ("answer accuracy (per reference point)", a.get("answer_accuracy")),
        ("answers passing all reference points",
         a.get("answer_all_points_pass_rate")),
        ("avg latency (s)", a.get("avg_latency_s")),
        ("p95 latency (s)", a.get("p95_latency_s")),
        ("avg tool calls", a.get("avg_tool_calls")),
        ("avg iterations", a.get("avg_iterations")),
        ("avg cost / question (USD)", a.get("avg_cost_usd")),
        ("total cost (USD)", a.get("total_cost_usd")),
    ]
    for label, value in summary:
        lines.append(f"| {label} | {_fmt(value)} |")
    lines.append("")

    if retrieval:
        lines += ["## Retrieval: per-question results", "",
                  "| ID | Repo | Type | P@5 | P@10 | R@5 | R@10 | MRR | "
                  "Hit@5 | Error |",
                  "|---|---|---|---|---|---|---|---|---|---|"]
        for q in retrieval.get("per_question", []):
            m = q.get("metrics", {})
            lines.append(
                f"| {q['question_id']} | {q['repo']} | {q['type']} | "
                f"{_fmt(m.get('precision@5'), 3)} | {_fmt(m.get('precision@10'), 3)} | "
                f"{_fmt(m.get('recall@5'), 3)} | {_fmt(m.get('recall@10'), 3)} | "
                f"{_fmt(m.get('mrr'), 3)} | {_fmt(m.get('hit_rate@5'), 3)} | "
                f"{q.get('error') or ''} |")
        lines += ["", "### Retrieval failures", ""]
        failures = [q for q in retrieval.get("per_question", [])
                    if q.get("error")
                    or q.get("metrics", {}).get("recall@5", 1) < 1.0]
        if not failures:
            lines.append("None - every question retrieved all expected files "
                         "in the top 5.")
        for q in failures:
            lines.append(f"- **{q['question_id']}** ({q['repo']}, {q['type']}): "
                         f"{q['question']}")
            if q.get("error"):
                lines.append(f"  - error: `{q['error']}`")
            else:
                lines.append(f"  - expected: {q.get('expected_files')}")
                lines.append(f"  - retrieved: {q.get('retrieved')}")
        lines.append("")

    if agent:
        lines += ["## Agent: per-question results", "",
                  "| ID | Repo | Type | Tools | Iter | Latency (s) | "
                  "In tok | Out tok | Cost (USD) | Cit | Points |",
                  "|---|---|---|---|---|---|---|---|---|---|---|"]
        for q in agent.get("per_question", []):
            if q.get("error"):
                lines.append(f"| {q['question_id']} | {q['repo']} | {q['type']} | "
                             f"- | - | - | - | - | - | - | ERROR |")
                continue
            rp = q["reference_points"]
            lines.append(
                f"| {q['question_id']} | {q['repo']} | {q['type']} | "
                f"{q['tool_calls']} | {q['iterations']} | "
                f"{_fmt(q['latency_s'], 1)} | "
                f"{q['input_tokens']} | {q['output_tokens']} | "
                f"{_fmt(q['cost_usd'], 6)} | "
                f"{_fmt(q['citation']['accuracy'], 2)} | "
                f"{rp['passed']}/{rp['total']} |")
        lines += ["", "### Agent failures", ""]
        failures = [q for q in agent.get("per_question", [])
                    if q.get("error") or q.get("judge_error")
                    or (q.get("citation")
                        and (q["citation"]["accuracy"] < 1.0
                             or q["reference_points"]["ratio"] < 1.0))]
        if not failures:
            lines.append("None - every answer cited only expected files and "
                         "covered every reference point.")
        for q in failures:
            lines.append(f"- **{q['question_id']}** ({q['repo']}, {q['type']}): "
                         f"{q['question']}")
            if q.get("error"):
                lines.append(f"  - error: `{q['error']}`")
                continue
            if q["citation"]["accuracy"] < 1.0:
                bad = [d for d in q["citation"]["details"] if not d["correct"]]
                lines.append(f"  - citation problems: {bad}")
            if q.get("judge_error"):
                lines.append(f"  - judge error: `{q['judge_error']}`")
            elif q["reference_points"]["ratio"] < 1.0:
                for point in (q.get("judge") or {}).get("points", []):
                    if not point["passed"]:
                        lines.append(
                            f"  - reference point FAILED: {point['point']} "
                            f"({point['reason']})")
            lines.append("")
    else:
        lines += ["## Agent suite", "",
                  "Not run yet - execute `python -m eval.run --suite agent` "
                  "(needs a Groq key).", ""]


    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_FILE.write_text("\n".join(lines), encoding="utf-8")
    print(f"Wrote {REPORT_FILE.relative_to(REPO_ROOT)}")
    return REPORT_FILE
