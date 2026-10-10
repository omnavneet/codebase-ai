"""Retrieval-only evaluation of the Codebase-AI backend (no LLM calls).

For each question:
  1. Index each pinned repo snapshot through the REAL indexing path
     (POST /api/projects -> POST /api/projects/{id}/upload -> poll the
     indexing job until ready). The snapshot is ``git archive`` of the pin,
     so every run indexes the same source.
  2. Run semantic search (POST /api/projects/{id}/search) with the question.
  3. Compute precision@5/10, recall@5/10, MRR and hit-rate@5/10 against the
     question's expected_files (eval/metrics.py).
  4. Aggregate as the macro-average over all questions.

Writes docs/eval/retrieval_results.json and refreshes docs/eval/report.md.
With gate=True the function returns non-zero when any aggregate is below the
thresholds in eval/config.json (committed baseline minus a small margin).
"""
import json
import time
from pathlib import Path
from typing import Dict, List

from eval.client import BackendClient
from eval.dataset import REPO_ROOT, load_config, question_list, repo_sha
from eval.metrics import compute_metrics

DOCS_DIR = REPO_ROOT / "docs" / "eval"
RESULTS_FILE = DOCS_DIR / "retrieval_results.json"


def _mean(values: List[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def run_retrieval_eval(backend_url: str = None,
                       ai_service_url: str = None,
                       gate: bool = False) -> int:
    """Run the retrieval suite. Returns 0 on success, non-zero on gate breach."""
    config = load_config()
    if backend_url:
        config["runtime"]["backend_url"] = backend_url
    if ai_service_url:
        config["runtime"]["ai_service_url"] = ai_service_url

    questions = question_list()
    k_values = config.get("k_values", [5, 10])
    client = BackendClient(config)

    per_question: List[dict] = []
    started_at = time.strftime("%Y-%m-%d %H:%M:%S")

    for repo in dict.fromkeys(q["repo"] for q in questions):
        repo_questions = [q for q in questions if q["repo"] == repo]
        print(f"[{repo}] indexing pinned {repo_sha(repo)[:12]} "
              f"({len(repo_questions)} questions) ...")
        project_id = client.ensure_indexed(repo)
        for q in repo_questions:
            record = {
                "question_id": q["id"],
                "repo": repo,
                "type": q["type"],
                "question": q["question"],
                "expected_files": q["expected_files"],
            }
            try:
                retrieved = client.search(project_id, q["question"])
                metrics = compute_metrics(retrieved, set(q["expected_files"]),
                                          k_values=k_values)
                record.update({
                    "retrieved": retrieved,
                    "metrics": metrics,
                    "error": None,
                })
                print(f"  {q['id']}: p@5={metrics['precision@5']:.3f} "
                      f"r@5={metrics['recall@5']:.3f} mrr={metrics['mrr']:.3f}")
            except Exception as exc:  # keep going; failure is recorded
                record.update({"retrieved": [], "metrics": {}, "error": str(exc)})
                print(f"  {q['id']}: ERROR {exc}")
            per_question.append(record)

    metric_keys = ([f"precision@{k}" for k in k_values]
                   + [f"recall@{k}" for k in k_values]
                   + [f"hit_rate@{k}" for k in k_values] + ["mrr"])
    ok_records = [r for r in per_question if not r["error"]]
    aggregate = {
        key: _mean([r["metrics"].get(key, 0.0) for r in ok_records])
        for key in metric_keys
    }
    aggregate["total_questions"] = len(per_question)
    aggregate["failed_questions"] = len(per_question) - len(ok_records)

    results = {
        "suite": "retrieval",
        "generated_at": started_at,
        "dataset_version": "see eval/questions.json metadata.version",
        "pins": {name: spec["sha"] for name, spec in config["repos"].items()},
        "aggregate": aggregate,
        "per_question": per_question,
    }
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    RESULTS_FILE.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nWrote {RESULTS_FILE.relative_to(REPO_ROOT)}")

    from eval.report import write_report
    write_report()

    print("\nRetrieval aggregates:")
    for key in metric_keys:
        print(f"  {key}: {aggregate[key]:.4f}")

    if not gate:
        return 0

    breached = []
    for key, threshold in config.get("thresholds", {}).items():
        if key.startswith("_"):
            continue
        if key in aggregate and aggregate[key] + 1e-9 < threshold:
            breached.append((key, aggregate[key], threshold))
    if breached or aggregate["failed_questions"]:
        for key, value, threshold in breached:
            print(f"GATE FAIL {key}: {value:.4f} < threshold {threshold:.4f}")
        if aggregate["failed_questions"]:
            print(f"GATE FAIL failed_questions: {aggregate['failed_questions']}")
        return 2
    print("GATE PASS: all thresholds met")
    return 0
