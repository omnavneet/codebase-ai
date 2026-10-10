"""Fetch the external benchmark repositories at their pinned commits.

The source of every external repo lives under ``eval/datasets/`` which is
git-ignored: only the pin (URL + commit SHA) in ``eval/config.json`` is
committed. Running this script twice is a no-op when the checkout already
matches the pin.

Usage:
    python -m eval.fetch_datasets
"""
import shutil
import subprocess
import sys
from pathlib import Path

from eval.dataset import load_config, repo_checkout_dir


def _git(args, cwd=None):
    return subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    ).stdout.strip()


def fetch_repo(name: str, spec: dict) -> Path:
    """Clone ``spec.url`` (if needed) and check out the pinned SHA."""
    url = spec["url"]
    sha = spec["sha"]
    dest = repo_checkout_dir(name)

    if not (dest / ".git").exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"[{name}] cloning {url} ...")
        # Full clone so any pinned (possibly non-branch) SHA is available.
        _git(["clone", "--quiet", url, str(dest)])

    head = _git(["rev-parse", "HEAD"], cwd=dest)
    if head != sha:
        print(f"[{name}] checking out pinned {sha[:12]} ...")
        _git(["checkout", "--quiet", "--force", sha], cwd=dest)
        head = _git(["rev-parse", "HEAD"], cwd=dest)
    if head != sha:
        raise SystemExit(f"[{name}] checkout is at {head}, expected {sha}")

    print(f"[{name}] ready at {sha}")
    return dest


def main() -> int:
    config = load_config()
    for name, spec in config["repos"].items():
        if spec["kind"] == "git":
            fetch_repo(name, spec)
        elif spec["kind"] == "local":
            print(f"[{name}] local repo, nothing to fetch (pinned {spec['sha'][:12]})")
        else:
            raise SystemExit(f"Unknown repo kind {spec['kind']!r} for {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
