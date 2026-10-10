"""Thin client over the REAL backend HTTP API.

Everything the evaluation does goes through the same endpoints the SPA uses
(auth -> create project -> upload ZIP -> indexing job -> search -> agent), so
the harness exercises the production code path, not a test double.

Authentication mirrors a real user: register, pull the verification link out
of MailHog, verify, then log in for a bearer token. A per-process cache lets
``--suite all`` reuse indexed projects between the retrieval and agent suites.
"""
import json
import re
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import requests

from eval.dataset import CACHE_DIR, load_config, materialize_zip

STATE_FILE = CACHE_DIR / "state.json"
_TOKEN_RE = re.compile(r"verify-email\?token=([A-Za-z0-9_\-%\.]+)")


class EvalClientError(RuntimeError):
    pass


class BackendClient:
    # repo name -> indexed project id, reused within one `python -m eval.run`
    _project_cache: Dict[str, str] = {}

    def __init__(self, config: Optional[dict] = None):
        self.config = config or load_config()
        rt = self.config["runtime"]
        self.backend_url = rt["backend_url"].rstrip("/")
        self.mailhog_url = rt["mailhog_url"].rstrip("/")
        self.poll_interval_s = rt.get("poll_interval_s", 2)
        self.index_timeout_s = rt.get("index_timeout_s", 900)
        self.request_timeout_s = rt.get("request_timeout_s", 120)
        self.rate_limit_backoff_s = rt.get("rate_limit_backoff_s", 65)
        self.token: Optional[str] = None
        self.email: Optional[str] = None

    # ---------------------------------------------------------------- HTTP
    def _request(self, method: str, url: str, *, auth: bool = False,
                 retries: int = 3, **kwargs) -> requests.Response:
        kwargs.setdefault("timeout", self.request_timeout_s)
        headers = dict(kwargs.pop("headers", {}))
        if auth and not self.token:
            self.ensure_auth()
        for attempt in range(retries + 1):
            if auth:
                headers["Authorization"] = f"Bearer {self.token}"
            resp = requests.request(method, url, headers=headers, **kwargs)
            if resp.status_code == 429 and attempt < retries:
                wait = int(resp.headers.get("Retry-After",
                                            self.rate_limit_backoff_s))
                print(f"  rate limited on {url}; sleeping {wait}s")
                time.sleep(wait)
                continue
            if resp.status_code == 401 and auth and attempt < retries:
                self.token = None          # expired token: re-auth and retry
                self.ensure_auth()
                continue
            return resp
        return resp

    # --------------------------------------------------------------- auth
    @staticmethod
    def _load_state() -> dict:
        if STATE_FILE.exists():
            try:
                return json.loads(STATE_FILE.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                pass
        return {}

    @staticmethod
    def _save_state(state: dict) -> None:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")

    def ensure_auth(self) -> str:
        if self.token:
            return self.token
        state = self._load_state()
        email = state.get("eval_user_email")
        password = state.get("eval_user_password")
        if not email:
            email = f"eval-user-{int(time.time())}@example.com"
            password = "eval-password-123"
            state.update(eval_user_email=email, eval_user_password=password)
            self._save_state(state)
        self.email = email

        resp = self._request("POST", f"{self.backend_url}/api/auth/login",
                             json={"email": email, "password": password})
        if resp.status_code == 200:
            self.token = resp.json()["accessToken"]
            return self.token

        # Not registered (or not yet verified): register / resend, then pull
        # the verification link from MailHog like a real user would.
        reg = self._request("POST", f"{self.backend_url}/api/auth/register",
                            json={"email": email, "password": password})
        if reg.status_code == 409:
            self._request("POST",
                          f"{self.backend_url}/api/auth/resend-verification",
                          json={"email": email})
        elif reg.status_code != 200:
            raise EvalClientError(
                f"register failed: {reg.status_code} {reg.text[:200]}")

        token = self._wait_for_verification_token(email)
        ver = self._request("GET", f"{self.backend_url}/api/auth/verify-email",
                            params={"token": token})
        if ver.status_code != 200:
            raise EvalClientError(
                f"verify-email failed: {ver.status_code} {ver.text[:200]}")

        resp = self._request("POST", f"{self.backend_url}/api/auth/login",
                             json={"email": email, "password": password})
        if resp.status_code != 200:
            raise EvalClientError(
                f"login after verification failed: {resp.status_code} "
                f"{resp.text[:200]}")
        self.token = resp.json()["accessToken"]
        return self.token

    def _wait_for_verification_token(self, email: str, timeout_s: int = 60) -> str:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            try:
                msgs = requests.get(f"{self.mailhog_url}/api/v2/messages",
                                    params={"limit": 20}, timeout=5).json()
                for item in msgs.get("items", []):
                    # MailHog v2: To is [{Mailbox, Domain, ...}] and the raw
                    # message lives under Content.Body.
                    addrs = []
                    for t in item.get("To") or []:
                        if isinstance(t, dict):
                            addrs.append(f"{t.get('Mailbox', '')}@"
                                         f"{t.get('Domain', '')}")
                        else:
                            addrs.append(str(t))
                    if not any(email.lower() in a.lower() for a in addrs):
                        continue
                    content = item.get("Content") or {}
                    body = content.get("Body") or item.get("Body") or ""
                    m = _TOKEN_RE.search(body)
                    if m:
                        return m.group(1)
            except (requests.RequestException, ValueError):
                pass
            time.sleep(2)
        raise EvalClientError(
            f"no verification mail for {email} within {timeout_s}s "
            f"(is MailHog running at {self.mailhog_url}?)")


    # ---------------------------------------------- projects and indexing
    def create_project(self, name: str) -> str:
        resp = self._request("POST", f"{self.backend_url}/api/projects",
                             auth=True, json={"name": name})
        if resp.status_code != 200:
            raise EvalClientError(
                f"create project failed: {resp.status_code} {resp.text[:200]}")
        return resp.json()["id"]

    def upload_and_wait(self, project_id: str, zip_path: Path) -> dict:
        """Upload the pinned ZIP and poll the indexing job until it settles."""
        with open(zip_path, "rb") as f:
            resp = self._request(
                "POST", f"{self.backend_url}/api/projects/{project_id}/upload",
                auth=True, files={"file": (zip_path.name, f, "application/zip")},
            )
        if resp.status_code not in (200, 202):
            raise EvalClientError(
                f"upload failed: {resp.status_code} {resp.text[:300]}")
        job = resp.json()
        job_id = job["id"]
        deadline = time.time() + self.index_timeout_s
        last = job
        while time.time() < deadline:
            if last.get("status") in ("ready", "error", "cancelled"):
                return last
            time.sleep(self.poll_interval_s)
            st = self._request(
                "GET",
                f"{self.backend_url}/api/projects/{project_id}/jobs/{job_id}",
                auth=True)
            if st.status_code != 200:
                raise EvalClientError(
                    f"job status failed: {st.status_code} {st.text[:200]}")
            last = st.json()
        raise EvalClientError(
            f"indexing job {job_id} not finished after {self.index_timeout_s}s "
            f"(last status {last.get('status')})")

    def ensure_indexed(self, repo_name: str, log=print) -> str:
        """Index the pinned snapshot of ``repo_name`` through the real path.

        Projects are reused across suites within one process; each fresh run
        creates a fresh project so the index always matches the pinned commit.
        """
        cached = self._project_cache.get(repo_name)
        if cached:
            log(f"  reusing indexed project {cached} for {repo_name}")
            return cached
        zip_path = materialize_zip(repo_name)
        project_id = self.create_project(f"eval-{repo_name}")
        log(f"  uploaded {zip_path.name} -> project {project_id}; indexing...")
        job = self.upload_and_wait(project_id, zip_path)
        if job.get("status") != "ready":
            raise EvalClientError(
                f"indexing {repo_name} ended with status {job.get('status')}: "
                f"{job.get('errorMessage')}")
        log(f"  index ready for {repo_name} ({job.get('progressPercent')}%)")
        self._project_cache[repo_name] = project_id
        return project_id

    # ------------------------------------------------- search and agent
    def search(self, project_id: str, query: str) -> List[str]:
        """Run semantic search; returns ranked project-relative file paths."""
        resp = self._request(
            "POST", f"{self.backend_url}/api/projects/{project_id}/search",
            auth=True, json={"query": query})
        if resp.status_code != 200:
            raise EvalClientError(
                f"search failed: {resp.status_code} {resp.text[:200]}")
        ranked: List[str] = []
        seen = set()
        for item in resp.json():
            path = item.get("filePath")
            if path and path not in seen:
                seen.add(path)
                ranked.append(path)
        return ranked

    def investigate(self, project_id: str, question: str) -> Tuple[dict, float]:
        """Call the real agent endpoint. Returns (response_json, latency_s)."""
        started = time.perf_counter()
        resp = self._request(
            "POST",
            f"{self.backend_url}/api/projects/{project_id}/agent/investigate",
            auth=True, json={"question": question},
            timeout=max(self.request_timeout_s, 300))
        latency = time.perf_counter() - started
        if resp.status_code != 200:
            raise EvalClientError(
                f"investigate failed: {resp.status_code} {resp.text[:300]}")
        return resp.json(), latency

    def delete_project(self, project_id: str) -> None:
        """Best-effort cleanup so repeated local runs don't pile up projects."""
        try:
            self._request("DELETE",
                          f"{self.backend_url}/api/projects/{project_id}",
                          auth=True)
        except requests.RequestException:
            pass




