"""
Evaluation harness for Codebase-AI retrieval and agent quality.

Usage:
    python -m eval.run                         # runs retrieval + agent (full)
    python -m eval.run --suite retrieval       # retrieval only (no LLM calls)
    python -m eval.run --suite agent           # agent only (needs Groq)
    python -m eval.run --verify-dataset        # verify expected files exist (no indexing)
    python -m eval.run --gate                    # quick CI gate check (exit nonzero if thresholds breached)
"""
import argparse
import sys
from eval.retrieval_eval import run_retrieval_eval
from eval.agent_eval import run_agent_eval


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="eval",
        description="Codebase-AI evaluation harness for retrieval and agent quality."
    )
    parser.add_argument(
        "--suite",
        choices=["retrieval", "agent", "all"],
        default="all",
        help="Which evaluation suite to run.",
    )
    parser.add_argument(
        "--verify-dataset",
        action="store_true",
        help="Verify that all expected_files in questions.json exist on disk.",
    )
    parser.add_argument(
        "--gate",
        action="store_true",
        help="Run retrieval eval and exit non-zero if thresholds are breached.",
    )
    parser.add_argument(
        "--backend-url",
        default="http://localhost:8080",
        help="Base URL of the backend API.",
    )
    parser.add_argument(
        "--ai-service-url",
        default="http://localhost:8000",
        help="Base URL of the AI service.",
    )

    args = parser.parse_args(argv)

    if args.verify_dataset:
        from eval.dataset import verify_dataset
        ok, missing = verify_dataset()
        if ok:
            print("All expected files present.")
            return 0
        else:
            print(f"MISSING files: {missing}")
            return 1

    if args.gate:
        from eval.dataset import verify_dataset
        ok, missing = verify_dataset()
        if not ok:
            print(f"Cannot run gate: {len(missing)} expected files missing.")
            return 1
        return run_retrieval_eval(args.backend_url, args.ai_service_url, gate=True)

    if args.suite in ("retrieval", "all"):
        rc = run_retrieval_eval(args.backend_url, args.ai_service_url)
        if rc != 0:
            return rc

    if args.suite in ("agent", "all"):
        rc = run_agent_eval(args.backend_url, args.ai_service_url)
        if rc != 0:
            return rc

    return 0


if __name__ == "__main__":
    sys.exit(main())
