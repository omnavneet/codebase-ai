"""Measure chat, Deep Investigate, and Debug on one synthetic repository."""
import json
import re
from pathlib import Path
from statistics import mean
from tempfile import TemporaryDirectory
from time import perf_counter
from zipfile import ZipFile

import requests

from eval.agent_eval import estimate_cost
from eval.citation_parser import normalize_citation_path, parse_citations
from eval.client import BackendClient, EvalClientError
from eval.dataset import REPO_ROOT, load_config

FIXTURE = Path(__file__).with_name("product_fixture.json")
OUTPUT = REPO_ROOT / "docs" / "eval" / "product_results.json"


def score_answer(answer, case, files, observed=None):
    citations = [ref for ref in parse_citations(answer) if ref.start_line is not None]
    if case.get("abstain"):
        abstained = re.fullmatch(r"\s*[*`]*NOT IN REPOSITORY[.*`\s]*", answer, re.IGNORECASE) is not None
        return {"answer_pass": abstained, "citation_pass": not citations, "abstained": abstained}

    expected_path, first, last = case["source"]
    valid = []
    for ref in citations:
        path = normalize_citation_path(ref.file_path)
        matches = [p for p in files if p == path or p.endswith("/" + path)]
        end = ref.end_line if ref.end_line is not None else ref.start_line
        backed = observed is None or any(
            source.get("file_id") and source.get("file_path") == expected_path
            and source["start_line"] <= ref.start_line <= end <= source["end_line"]
            for source in observed
        )
        valid.append(backed and matches == [expected_path]
                     and 1 <= ref.start_line <= end <= len(files[expected_path].splitlines())
                     and ref.start_line <= last and end >= first)
    return {"answer_pass": all(re.search(pattern, answer, re.IGNORECASE) is not None
                               for pattern in case["required_patterns"]),
            "citation_pass": bool(valid) and all(valid), "abstained": None}


def run_product_eval(backend_url=None, output=OUTPUT):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    config = load_config()
    if backend_url:
        config["runtime"]["backend_url"] = backend_url
    client = BackendClient(config)
    records = []
    conversations = {}
    failed_conversations = set()
    model = config["agent"]["model"]
    project = None
    try:
        client.ensure_auth()
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "product-fixture.zip"
            with ZipFile(archive, "w") as bundle:
                for path, source in fixture["files"].items():
                    bundle.writestr(path, source)
            project = client.create_project("eval-product-fixture")
            job = client.upload_and_wait(project, archive)
        if job.get("status") != "ready":
            raise EvalClientError(f"Fixture indexing ended with status {job.get('status')}")

        for case in fixture["cases"]:
            for mode in case["modes"]:
                rec = {"case": case["id"], "mode": mode, "error": None, "latency_s": None,
                       "cost_usd": None, "abstain_expected": case.get("abstain", False)}
                try:
                    question = case["question"]
                    if mode == "chat":
                        name = case["conversation"]
                        if name in failed_conversations:
                            raise EvalClientError("Previous conversation turn failed")
                        if name not in conversations:
                            conversations[name] = client.post_project(project, "conversations")["id"]
                        endpoint = f"conversations/{conversations[name]}/messages"
                        payload = {"content": question}
                    elif mode == "deep":
                        endpoint, payload = "agent/investigate", {"question": question}
                    else:
                        endpoint = "agent/debug"
                        payload = {"issueDescription": question, "stackTrace": case["stack_trace"]}
                    started = perf_counter()
                    try:
                        response = client.post_project(project, endpoint, payload)
                    finally:
                        rec["latency_s"] = round(perf_counter() - started, 3)
                    answer = response.get("answer") or ""
                    rec.update(score_answer(answer, case, fixture["files"], response.get("citations") or []), answer=answer,
                               usage=response.get("usage"))
                    usage = rec["usage"] or {}
                    if mode == "deep" and usage.get("total_tokens") and model in config["pricing"]["models"]:
                        rec["cost_usd"] = estimate_cost(model, usage["input_tokens"], usage["output_tokens"], config)
                except (EvalClientError, requests.RequestException, KeyError, ValueError) as exc:
                    rec["error"] = str(exc)
                    if mode == "chat":
                        failed_conversations.add(case["conversation"])
                records.append(rec)
    except (EvalClientError, requests.RequestException) as exc:
        print(f"Product fixture setup failed: {exc}")
        return 1
    finally:
        if project is not None:
            client.delete_project(project)

    aggregate = {}
    for mode in ("chat", "deep", "debug"):
        rows = [row for row in records if row["mode"] == mode]
        supported = [row for row in rows if not row["abstain_expected"]]
        abstention = [row for row in rows if row["abstain_expected"]]
        latencies = [row["latency_s"] for row in rows if not row["error"]]
        costs = [row["cost_usd"] for row in rows if row["cost_usd"] is not None]
        aggregate[mode] = {
            "attempted": len(rows), "failed": sum(row["error"] is not None for row in rows),
            "answer_pass_rate": mean(bool(row.get("answer_pass")) for row in supported),
            "citation_pass_rate": mean(bool(row.get("citation_pass")) for row in supported),
            "grounded_answer_pass_rate": mean(bool(row.get("answer_pass") and row.get("citation_pass")) for row in supported),
            "abstention_pass_rate": mean(bool(row.get("abstained")) for row in abstention) if abstention else None,
            "mean_success_latency_s": mean(latencies) if latencies else None,
            "mean_reported_cost_usd": mean(costs) if costs else None,
            "cost_samples": len(costs),
        }
    output.parent.mkdir(parents=True, exist_ok=True)
    shared = {case["id"] for case in fixture["cases"] if case["modes"] == ["chat", "deep"]}
    output.write_text(json.dumps({"fixture_version": fixture["version"], "priced_deep_model": model,
                                 "aggregate": aggregate, "shared_cases": sorted(shared), "cases": records},
                                indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {output}")
    return int(any(row["error"] for row in records))
