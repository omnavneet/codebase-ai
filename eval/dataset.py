"""Dataset access for the evaluation harness.

Owns the versioned question file (``eval/questions.json``), the pinned repo
definitions in ``eval/config.json``, and the two operations every suite needs:

* ``materialize_zip(repo)`` - build the exact pinned snapshot that gets
  uploaded through the real indexing path (``git archive`` of the pinned SHA;
  the snapshot is never committed, ``*.zip`` is git-ignored).
* ``verify_dataset()`` - quick script (``python -m eval.run --verify-dataset``)
  that checks every ``expected_files`` entry exists **at the pinned commit**
  and every ``expected_line_ranges`` entry lies inside the file.

NOTE FOR HUMANS: expected files and line ranges in questions.json are a
baseline aid, not gospel. Manually verify them before trusting the numbers
(metadata.verification_note in questions.json repeats this).
"""
import json
import subprocess
from functools import lru_cache
from pathlib import Path
from typing import List, Optional, Tuple

EVAL_DIR = Path(__file__).parent
REPO_ROOT = EVAL_DIR.parent
QUESTIONS_FILE = EVAL_DIR / "questions.json"
CONFIG_FILE = EVAL_DIR / "config.json"
CACHE_DIR = EVAL_DIR / ".cache"


def _git(args: List[str], cwd: Path) -> str:
    return subprocess.run(
        ["git", *args], cwd=str(cwd), capture_output=True, text=True, check=True
    ).stdout


def _git_ok(args: List[str], cwd: Path) -> bool:
    return subprocess.run(
        ["git", *args], cwd=str(cwd), capture_output=True, text=True
    ).returncode == 0


@lru_cache(maxsize=1)
def load_config() -> dict:
    with open(CONFIG_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def load_questions() -> dict:
    with open(QUESTIONS_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def question_list() -> List[dict]:
    return load_questions().get("questions", [])


def repo_checkout_dir(repo_name: str) -> Path:
    """Working directory of the pinned checkout for a configured repo."""
    spec = load_config()["repos"][repo_name]
    path = Path(spec["path"])
    return path if path.is_absolute() else (REPO_ROOT / path)


def repo_sha(repo_name: str) -> str:
    return load_config()["repos"][repo_name]["sha"]


def file_exists_at_pin(repo_name: str, rel_path: str) -> bool:
    """True when ``rel_path`` exists in the repo at the pinned commit."""
    spec = load_config()["repos"][repo_name]
    sha = spec["sha"]
    cwd = REPO_ROOT if spec["kind"] == "local" else repo_checkout_dir(repo_name)
    # The working tree may differ from the pin; the pin is the source of
    # truth, so ask git about the commit itself.
    return _git_ok(["cat-file", "-e", f"{sha}:{rel_path}"], cwd)


def file_line_count_at_pin(repo_name: str, rel_path: str) -> Optional[int]:
    """Number of lines of ``rel_path`` at the pinned commit (None if absent)."""
    spec = load_config()["repos"][repo_name]
    cwd = REPO_ROOT if spec["kind"] == "local" else repo_checkout_dir(repo_name)
    try:
        text = _git(["show", f"{spec['sha']}:{rel_path}"], cwd)
    except subprocess.CalledProcessError:
        return None
    return len(text.splitlines())


def materialize_zip(repo_name: str) -> Path:
    """Build (or reuse) the pinned snapshot ZIP used to index this repo.

    Uses ``git archive`` of the pinned SHA, so the uploaded source is exactly
    the pinned commit - never the dirty working tree - and is fully
    deterministic across machines and runs.
    """
    spec = load_config()["repos"][repo_name]
    sha = spec["sha"]
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    zip_path = CACHE_DIR / f"{repo_name}-{sha[:12]}.zip"
    if zip_path.exists() and zip_path.stat().st_size > 0:
        return zip_path

    cwd = REPO_ROOT if spec["kind"] == "local" else repo_checkout_dir(repo_name)
    if spec["kind"] == "git" and not (cwd / ".git").exists():
        raise RuntimeError(
            f"Repo {repo_name} is not fetched yet. Run: python -m eval.fetch_datasets"
        )
    with open(zip_path, "wb") as f:
        subprocess.run(
            ["git", "archive", "--format=zip", "-o", str(zip_path), sha],
            cwd=str(cwd), check=True, capture_output=True,
        )
    if not zip_path.exists() or zip_path.stat().st_size == 0:
        raise RuntimeError(f"git archive produced no output for {repo_name}@{sha}")
    return zip_path


def verify_dataset() -> Tuple[bool, List[str]]:
    """Verify every expected file (and line range) against the pinned commits.

    Returns ``(ok, problems)`` where problems are human-readable strings.
    This is deliberately a plain script path (``python -m eval.run
    --verify-dataset``), not a unit test.
    """
    problems: List[str] = []
    questions = question_list()
    config = load_config()

    known_repos = set(config["repos"].keys())
    for q in questions:
        qid = q.get("id", "<no-id>")
        for field in ("id", "repo", "question", "type",
                      "expected_files", "reference_answer_points"):
            if field not in q:
                problems.append(f"{qid}: missing required field {field!r}")
        repo = q.get("repo")
        if repo not in known_repos:
            problems.append(f"{qid}: unknown repo {repo!r}")
            continue
        if q.get("type") not in ("locate", "explain", "trace-call-flow"):
            problems.append(f"{qid}: bad type {q.get('type')!r}")
        points = q.get("reference_answer_points", [])
        if not (2 <= len(points) <= 3):
            problems.append(
                f"{qid}: reference_answer_points must have 2-3 facts, got {len(points)}"
            )
        for ef in q.get("expected_files", []):
            if not file_exists_at_pin(repo, ef):
                problems.append(f"{qid}: {ef} missing in {repo}@{repo_sha(repo)[:12]}")
        for rng in q.get("expected_line_ranges", []) or []:
            if not (isinstance(rng, list) and len(rng) == 2 and rng[0] <= rng[1]):
                problems.append(f"{qid}: malformed line range {rng}")
                continue
            # Bound-check the range against each expected file's length at the
            # pin; a range beyond EOF means the baseline line numbers are stale.
            for ef in q.get("expected_files", []):
                n = file_line_count_at_pin(repo, ef)
                if n is not None and rng[1] > n:
                    problems.append(
                        f"{qid}: range {rng} exceeds {ef} ({n} lines) at pin"
                    )
    return len(problems) == 0, problems


