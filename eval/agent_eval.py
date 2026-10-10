"""Agent evaluation: real Groq-backed investigations through the backend.

Per question this suite records tool-call count, iterations, wall-clock
latency, input/output tokens (from Groq usage surfaced by the agent) and the
estimated cost from the price table in ``eval/config.json``.

Two correctness signals:
  * Citation correctness - ``eval/citation_parser.py`` extracts file/line
    citations from the answer; each cited file must exist at the pinned
    commit AND be one of the question's expected_files.
  * Answer correctness - an LLM judge at temperature 0 with a fixed prompt
    returns pass/fail per reference answer point; the raw judge output is
    logged in the results JSON for audit.

Writes docs/eval/agent_results.json and refreshes docs/eval/report.md.
"""
import json
import math
import os
import re
import time
from pathlib import Path
from typing import List, Optional, Set

import requests

from eval.citation_parser import citation_matches_expected, parse_citations
from eval.client import BackendClient
from eval.dataset import (
    REPO_ROOT, file_exists_at_pin, load_config, question_list,
)

DOCS_DIR = REPO_ROOT / "docs" / "eval"
RESULTS_FILE = DOCS_DIR / "agent_results.json"
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

# Fixed judge prompt: identical wording, ordering and format for every run.
JUDGE_SYSTEM_PROMPT = """You are a strict codebase QA judge.
Given a QUESTION, a list of REFERENCE POINTS (facts a correct answer must
cover), and a CANDIDATE ANSWER, decide for EACH reference point whether the
answer covers it. Accept equivalent phrasing and correct partial detail;
reject contradictions or claims not supported by the reference point.
Respond with ONLY a JSON object, no markdown fences, in exactly this shape:
{"points": [{"point": "<reference point>", "passed": true, "reason": "<short reason>"}, ...]}"""


def groq_api_key() -> Optional[str]:
    """Groq key from the environment, falling back to the repo .env files."""
    key = os.getenv("GROQ_API_KEY")
    if key:
        return key.strip()
    for env_file in (REPO_ROOT / "ai-service" / ".env", REPO_ROOT / ".env"):
        if env_file.exists():
            # utf-8-sig tolerates a BOM, which python-dotenv chokes on.
            text = env_file.read_text(encoding="utf-8-sig")
            m = re.search(r"^GROQ_API_KEY=([^\r\n]+)", text, re.MULTILINE)
            if m:
                return m.group(1).strip()
    return None


def _extract_json(text: str) -> dict:
    """Pull the outermost JSON object out of a model response."""
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError(f"judge returned no JSON: {text[:200]!r}")
    return json.loads(text[start:end + 1])


def judge_answer(question: str, answer: str, reference_points: List[str],
                 api_key: str, config: dict) -> dict:
    """LLM judge at temperature 0 with a fixed prompt; raw output is kept."""
    judge_cfg = config["judge"]
    points_block = "\n".join(f"{i + 1}. {p}"
                             for i, p in enumerate(reference_points))
    user_prompt = (f"QUESTION:\n{question}\n\n"
                   f"REFERENCE POINTS:\n{points_block}\n\n"
                   f"CANDIDATE ANSWER:\n{answer[:8000]}")
    resp = requests.post(
        GROQ_URL,
        headers={"Authorization": f"Bearer {api_key}",
                 "Content-Type": "application/json"},
        json={
            "model": judge_cfg["model"],
            "temperature": judge_cfg.get("temperature", 0),
            "messages": [{"role": "system", "content": JUDGE_SYSTEM_PROMPT},
                         {"role": "user", "content": user_prompt}],
        },
        timeout=120,
    )
    resp.raise_for_status()
    data = resp.json()
    content = data["choices"][0]["message"]["content"]
    parsed = _extract_json(content)
    verdicts = parsed.get("points", [])
    # Keep only the audit-relevant fields, plus the untouched raw response.
    return {
        "model": judge_cfg["model"],
        "temperature": judge_cfg.get("temperature", 0),
        "raw": content,
        "points": [
            {"point": v.get("point", ""), "passed": bool(v.get("passed")),
             "reason": v.get("reason", "")}
            for v in verdicts
        ],
        "usage": data.get("usage", {}),
    }


def estimate_cost(model: str, input_tokens: int, output_tokens: int,
                  config: dict) -> float:
    """Cost in USD from the price table in eval/config.json."""
    table = config["pricing"]["models"].get(model)
    if not table:
        return 0.0
    return (input_tokens / 1_000_000.0 * table["input_per_1m"]
            + output_tokens / 1_000_000.0 * table["output_per_1m"])


def files_at_pin(repo_name: str) -> Set[str]:
    """All file paths of the repo at the pinned commit (for exists-checks)."""
    from eval.dataset import load_config as _lc, REPO_ROOT as _root
    spec = _lc()["repos"][repo_name]
    cwd = _root if spec["kind"] == "local" else (
        _root / spec["path"] if not Path(spec["path"]).is_absolute()
        else Path(spec["path"]))
    import subprocess
    out = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", spec["sha"]],
        cwd=str(cwd), capture_output=True, text=True, check=True,
    ).stdout
    return {line.strip() for line in out.splitlines() if line.strip()}


def score_citations(answer: str, expected_files: List[str],
                    repo_files: Set[str]) -> dict:
    """Parse citations; a citation is correct iff the file exists at the pin
    AND is listed in the question's expected_files."""
    citations = parse_citations(answer)
    details = []
    correct = 0
    for c in citations:
        path = c.file_path.replace("\\", "/").lstrip("./")
        exists = (path in repo_files
                  or any(f.endswith("/" + path) for f in repo_files))
        in_expected = citation_matches_expected(c.file_path, expected_files)
        ok = bool(exists and in_expected)
        correct += 1 if ok else 0
        details.append({
            "file": c.file_path,
            "start_line": c.start_line,
            "end_line": c.end_line,
            "exists_at_pin": exists,
            "in_expected_files": in_expected,
            "correct": ok,
        })
    return {
        "accuracy": correct / len(citations) if citations else 0.0,
        "cited": len(citations),
        "correct": correct,
        "details": details,
    }


def _score_verdicts(verdicts: List[dict],
                    reference_points: List[str]) -> dict:
    """Pass ratio over the reference points (index-aligned, length-checked)."""
    if len(verdicts) != len(reference_points):
        return {
            "passed": 0,
            "total": len(reference_points),
            "ratio": 0.0,
            "length_mismatch": True,
        }
    passed = sum(1 for v in verdicts if v.get("passed"))
    return {
        "passed": passed,
        "total": len(reference_points),
        "ratio": passed / len(reference_points) if reference_points else 0.0,
        "length_mismatch": False,
    }


def run_agent_eval(backend_url: str = None, ai_service_url: str = None,
                   gate: bool = False) -> int:
    """Run the agent suite. Returns 0 on success, non-zero on hard failure."""
    config = load_config()
    if backend_url:
        config["runtime"]["backend_url"] = backend_url
    if ai_service_url:
        config["runtime"]["ai_service_url"] = ai_service_url

    api_key = groq_api_key()
    if not api_key:
        print("GROQ_API_KEY is not set; the agent suite needs a Groq key.")
        return 1

    questions = question_list()
    client = BackendClient(config)
    agent_model = config["agent"]["model"]
    started_at = time.strftime("%Y-%m-%d %H:%M:%S")
    per_question: List[dict] = []
    repo_file_cache: dict = {}

    for repo in dict.fromkeys(q["repo"] for q in questions):
        repo_questions = [q for q in questions if q["repo"] == repo]
        print(f"[{repo}] indexing pinned snapshot for {len(repo_questions)} "
              f"agent questions ...")
        project_id = client.ensure_indexed(repo)
        repo_file_cache[repo] = files_at_pin(repo)

        for q in repo_questions:
            print(f"  {q['id']}: {q['question'][:70]}...")
            rec = {
                "question_id": q["id"],
                "repo": repo,
                "type": q["type"],
                "question": q["question"],
                "expected_files": q["expected_files"],
            }
            try:
                resp, latency = client.investigate(project_id, q["question"])
            except Exception as exc:
                rec.update({"error": str(exc), "latency_s": None})
                per_question.append(rec)
                print(f"    ERROR {exc}")
                continue

            answer = resp.get("answer") or ""
            usage = resp.get("usage") or {}
            in_tok = int(usage.get("input_tokens") or 0)
            out_tok = int(usage.get("output_tokens") or 0)
            cost = estimate_cost(agent_model, in_tok, out_tok, config)
            citations = score_citations(answer, q["expected_files"],
                                        repo_file_cache[repo])
            try:
                judge = judge_answer(q["question"], answer,
                                     q["reference_answer_points"],
                                     api_key, config)
                verdict = _score_verdicts(judge["points"],
                                          q["reference_answer_points"])
                judge_info = {k: judge[k] for k in
                              ("model", "temperature", "raw", "points", "usage")}
                judge_error = None
            except Exception as exc:
                judge_info, judge_error = None, str(exc)
                verdict = {"passed": 0,
                           "total": len(q["reference_answer_points"]),
                           "ratio": 0.0, "length_mismatch": False}

            rec.update({
                "error": None,
                "answer": answer,
                "tool_calls": resp.get("tool_calls", 0),
                "iterations": resp.get("iterations", 0),
                "truncated": bool(resp.get("truncated", False)),
                "latency_s": round(latency, 3),
                "input_tokens": in_tok,
                "output_tokens": out_tok,
                "cost_usd": round(cost, 8),
                "citation": citations,
                "judge": judge_info,
                "judge_error": judge_error,
                "reference_points": {
                    "passed": verdict["passed"],
                    "total": verdict["total"],
                    "ratio": verdict["ratio"],
                    "length_mismatch": verdict["length_mismatch"],
                },
            })
            per_question.append(rec)
            print(f"    tools={rec['tool_calls']} it={rec['iterations']} "
                  f"{latency:.1f}s tok={in_tok}/{out_tok} "
                  f"cit={citations['accuracy']:.2f} "
                  f"pts={verdict['passed']}/{verdict['total']}")

    ok = [r for r in per_question if not r.get("error")]
    latencies = sorted(r["latency_s"] for r in ok)
    p95 = (latencies[math.ceil(0.95 * len(latencies)) - 1]
           if latencies else 0.0)
    n_ok = len(ok) or 1
    aggregate = {
        "citation_accuracy": sum(r["citation"]["accuracy"] for r in ok) / n_ok,
        "answer_accuracy": sum(r["reference_points"]["ratio"] for r in ok) / n_ok,
        "answer_all_points_pass_rate": (
            sum(1 for r in ok if r["reference_points"]["ratio"] == 1.0)
            / len(ok) if ok else 0.0),
        "avg_latency_s": sum(r["latency_s"] for r in ok) / n_ok,
        "p95_latency_s": p95,
        "avg_tool_calls": sum(r["tool_calls"] for r in ok) / n_ok,
        "avg_iterations": sum(r["iterations"] for r in ok) / n_ok,
        "avg_cost_usd": sum(r["cost_usd"] for r in ok) / n_ok,
        "total_cost_usd": sum(r["cost_usd"] for r in ok),
        "input_tokens_total": sum(r["input_tokens"] for r in ok),
        "output_tokens_total": sum(r["output_tokens"] for r in ok),
        "total_questions": len(per_question),
        "failed_questions": len(per_question) - len(ok),
        "judge_errors": sum(1 for r in per_question if r.get("judge_error")),
    }

    results = {
        "suite": "agent",
        "generated_at": started_at,
        "agent_model": agent_model,
        "judge_model": config["judge"]["model"],
        "pins": {name: spec["sha"] for name, spec in config["repos"].items()},
        "aggregate": aggregate,
        "per_question": per_question,
    }
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    RESULTS_FILE.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nWrote {RESULTS_FILE.relative_to(REPO_ROOT)}")

    from eval.report import write_report
    write_report()

    print("\nAgent aggregates:")
    for key, value in aggregate.items():
        print(f"  {key}: {value}")
    return 0 if not aggregate["failed_questions"] else 1



