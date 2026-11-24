"""Regression checks for the evaluation's evidence and failure handling."""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from eval import agent_eval, retrieval_eval


def test_citation_requires_unique_path_valid_lines_and_annotated_overlap(monkeypatch):
    monkeypatch.setattr(agent_eval, "file_line_count_at_pin", lambda repo, path: 100)
    files = {"src/api/router.py", "src/admin/router.py", "src/unique.py"}
    expected = ["src/api/router.py"]

    def score(citation):
        return agent_eval.score_citations(citation, expected, files, "test", [[20, 30]])

    assert score("src/api/router.py:22-25")["accuracy"] == 1
    assert score("api/router.py:22")["accuracy"] == 1
    assert score("router.py:22")["details"][0]["ambiguous"]
    assert score("src/api/router.py:999999")["accuracy"] == 0
    assert score("src/api/router.py:30-20")["accuracy"] == 0
    assert score("src/api/router.py:10")["accuracy"] == 0
    assert score("src/api/router.py")["accuracy"] == 0
    assert score("src/unique.py:22")["accuracy"] == 0


def test_citation_without_annotation_reports_support_unknown(monkeypatch):
    monkeypatch.setattr(agent_eval, "file_line_count_at_pin", lambda repo, path: 10)
    result = agent_eval.score_citations("file.py:3", ["file.py"], {"file.py"}, "test")
    assert result["accuracy"] == 1
    assert result["details"][0]["matches_annotated_span"] is None


def test_citation_bounds_use_pinned_contents():
    path = "backend/src/main/java/com/codebaseai/backend/config/SecurityConfig.java"
    result = agent_eval.score_citations(f"{path}:999999", [path], {path}, "codebase-ai")
    assert result["accuracy"] == 0
    assert result["details"][0]["valid_lines"] is False


def test_retrieval_gate_rejects_all_irrelevant_responses(monkeypatch, tmp_path):
    class Client:
        def __init__(self, config):
            pass

        def ensure_indexed(self, repo):
            return "project"

        def search(self, project, question):
            return ["irrelevant.py"]

    config = {"runtime": {}, "repos": {"test": {"sha": "deadbeef"}},
              "thresholds": {"mrr": 0.0}, "k_values": [5, 10]}
    questions = [{"id": "q1", "repo": "test", "type": "locate",
                  "question": "Where?", "expected_files": ["expected.py"]}]
    monkeypatch.setattr(retrieval_eval, "load_config", lambda: config)
    monkeypatch.setattr(retrieval_eval, "question_list", lambda: questions)
    monkeypatch.setattr(retrieval_eval, "repo_sha", lambda repo: "deadbeef")
    monkeypatch.setattr(retrieval_eval, "BackendClient", Client)
    monkeypatch.setattr(retrieval_eval, "DOCS_DIR", tmp_path)
    monkeypatch.setattr(retrieval_eval, "RESULTS_FILE", tmp_path / "results.json")
    monkeypatch.setattr(retrieval_eval, "REPO_ROOT", tmp_path)
    import eval.report
    monkeypatch.setattr(eval.report, "write_report", lambda: None)

    assert retrieval_eval.run_retrieval_eval(gate=True) == 2
    assert json.loads((tmp_path / "results.json").read_text())["aggregate"]["mrr"] == 0


def test_retrieval_reports_each_question_when_indexing_fails(monkeypatch, tmp_path):
    class Client:
        def __init__(self, config):
            pass

        def ensure_indexed(self, repo):
            raise RuntimeError("index unavailable")

    config = {"runtime": {}, "repos": {"test": {"sha": "deadbeef"}},
              "thresholds": {"mrr": 0.0}, "k_values": [5, 10]}
    questions = [{"id": str(i), "repo": "test", "type": "locate",
                  "question": "Where?", "expected_files": ["expected.py"]}
                 for i in range(2)]
    monkeypatch.setattr(retrieval_eval, "load_config", lambda: config)
    monkeypatch.setattr(retrieval_eval, "question_list", lambda: questions)
    monkeypatch.setattr(retrieval_eval, "repo_sha", lambda repo: "deadbeef")
    monkeypatch.setattr(retrieval_eval, "BackendClient", Client)
    monkeypatch.setattr(retrieval_eval, "DOCS_DIR", tmp_path)
    monkeypatch.setattr(retrieval_eval, "RESULTS_FILE", tmp_path / "results.json")
    monkeypatch.setattr(retrieval_eval, "REPO_ROOT", tmp_path)
    import eval.report
    monkeypatch.setattr(eval.report, "write_report", lambda: None)

    assert retrieval_eval.run_retrieval_eval(gate=True) == 2
    result = json.loads((tmp_path / "results.json").read_text())
    assert result["aggregate"]["total_questions"] == 2
    assert result["aggregate"]["failed_questions"] == 2
    assert all("index unavailable" in item["error"] for item in result["per_question"])


def test_agent_failed_request_counts_toward_quality_denominator(monkeypatch, tmp_path):
    class Client:
        def __init__(self, config):
            pass

        def ensure_indexed(self, repo):
            return "project"

        def investigate(self, project, question):
            if question == "fails":
                raise RuntimeError("connection lost")
            return {"answer": "file.py:1", "usage": {}}, 1.0

    config = {"runtime": {}, "repos": {"test": {"sha": "deadbeef"}},
              "agent": {"model": "test"}, "judge": {"model": "test"},
              "pricing": {"models": {}}}
    questions = [{"id": str(i), "repo": "test", "type": "locate",
                  "question": question, "expected_files": ["file.py"],
                  "reference_answer_points": ["fact"]}
                 for i, question in enumerate(("works", "fails"))]
    monkeypatch.setattr(agent_eval, "load_config", lambda: config)
    monkeypatch.setattr(agent_eval, "question_list", lambda: questions)
    monkeypatch.setattr(agent_eval, "BackendClient", Client)
    monkeypatch.setattr(agent_eval, "groq_api_key", lambda: "test")
    monkeypatch.setattr(agent_eval, "files_at_pin", lambda repo: {"file.py"})
    monkeypatch.setattr(agent_eval, "file_line_count_at_pin", lambda repo, path: 1)
    monkeypatch.setattr(agent_eval, "judge_answer", lambda *args: {
        "model": "test", "temperature": 0, "raw": "", "usage": {},
        "points": [{"point": "fact", "passed": True}],
    })
    monkeypatch.setattr(agent_eval, "DOCS_DIR", tmp_path)
    monkeypatch.setattr(agent_eval, "RESULTS_FILE", tmp_path / "results.json")
    monkeypatch.setattr(agent_eval, "REPO_ROOT", tmp_path)
    import eval.report
    monkeypatch.setattr(eval.report, "write_report", lambda: None)

    assert agent_eval.run_agent_eval() == 1
    aggregate = json.loads((tmp_path / "results.json").read_text())["aggregate"]
    assert aggregate["citation_accuracy"] == 0.5
    assert aggregate["answer_accuracy"] == 0.5
    assert aggregate["answer_all_points_pass_rate"] == 0.5
