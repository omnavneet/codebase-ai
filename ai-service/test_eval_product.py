"""Exercise the product benchmark through a scripted loopback HTTP backend."""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from eval import client, product_eval


def test_product_eval_exercises_all_modes_and_preserves_followup(monkeypatch, tmp_path):
    monkeypatch.setattr(client, "STATE_FILE", tmp_path / "state.json")
    calls = []
    prefix = "/api/projects/fixture"

    class Backend(BaseHTTPRequestHandler):
        def reply(self, data):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(data).encode())

        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            if self.path == "/api/auth/login":
                self.reply({"accessToken": "fixture-token"})
                return
            assert self.headers["Authorization"] == "Bearer fixture-token"
            if self.path == f"{prefix}/upload":
                assert b"billing/receipts.py" in raw and b"shipping/labels.py" in raw
                self.reply({"id": "job", "status": "ready"})
                return
            payload = json.loads(raw) if raw else {}
            calls.append((self.path, payload))
            if self.path == "/api/projects":
                self.reply({"id": "fixture"})
            elif self.path == f"{prefix}/conversations":
                self.reply({"id": f"chat-{len(calls)}"})
            else:
                question = next(iter(payload.values()))
                if self.path.endswith("/debug"):
                    assert "line 5" in payload["stackTrace"]
                if "provider" in question:
                    answer = "NOT IN REPOSITORY"
                elif "negative" in question or "-7" in question:
                    answer = "Negative numbers raise ValueError at billing/receipts.py:5."
                else:
                    answer = "Use rcpt_ and at least six digits, billing/receipts.py:1-6."
                result = {"answer": answer, "citations": [] if "provider" in question else [{
                    "file_id": "fixture-source", "file_path": "billing/receipts.py", "start_line": 1, "end_line": 6,
                }]}
                if self.path.endswith("/investigate"):
                    result["usage"] = {"total_tokens": 120, "input_tokens": 100, "output_tokens": 20}
                self.reply(result)

        def do_DELETE(self):
            assert self.path == prefix
            calls.append((self.path, None))
            self.reply({})

        def log_message(self, *_):
            pass

    output = tmp_path / "results.json"
    with ThreadingHTTPServer(("127.0.0.1", 0), Backend) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            assert product_eval.run_product_eval(f"http://127.0.0.1:{server.server_port}", output) == 0
        finally:
            server.shutdown()
            thread.join(timeout=5)

    report = json.loads(output.read_text(encoding="utf-8"))
    chat = [path for path, _ in calls if path.endswith("/messages")]
    assert chat[0] == chat[1] != chat[2]
    assert calls[-1] == (prefix, None)
    assert len(report["cases"]) == 6
    for mode in ("chat", "deep", "debug"):
        assert report["aggregate"][mode]["grounded_answer_pass_rate"] == 1
    assert report["aggregate"]["deep"]["abstention_pass_rate"] == 1
    assert report["aggregate"]["deep"]["cost_samples"] == 2
    assert report["aggregate"]["chat"]["mean_reported_cost_usd"] is None


def test_product_rubric_rejects_unsupported_citation_and_guess():
    fixture = json.loads(product_eval.FIXTURE.read_text(encoding="utf-8"))
    facts, _, abstention, _ = fixture["cases"]
    candidate = "Use rcpt_ with six digits at billing/receipts.py:7."
    verdict = product_eval.score_answer(candidate, facts, fixture["files"])
    assert verdict["answer_pass"] and not verdict["citation_pass"]
    candidate = "Use rcpt_ with six digits at billing/receipts.py:6."
    verdict = product_eval.score_answer(candidate, facts, fixture["files"], [])
    assert verdict["answer_pass"] and not verdict["citation_pass"]
    assert not product_eval.score_answer("NOT IN REPOSITORY, probably Stripe.", abstention, fixture["files"])["abstained"]
