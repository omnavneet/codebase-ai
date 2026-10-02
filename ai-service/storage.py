"""Read-only access to the project files the backend uploaded.

The backend decides where a project's files live; the AI service only reads
them. The provider is chosen with APP_STORAGE_PROVIDER (default: local):

    local - the shared upload directory, UPLOAD_DIR/<projectId>/<path>
    s3    - s3://<APP_S3_BUCKET>/<APP_S3_PREFIX>/<projectId>/<path>

Both providers validate the caller's path with the same rules before touching
the filesystem or S3, so a read can never escape the requested project.
"""

import os
import posixpath
from pathlib import Path
from typing import Dict, List, Optional

# S3 error codes that mean "this object is not there" rather than "something is
# wrong". Anything else (AccessDenied, networking) must surface as an error.
_MISSING_OBJECT_CODES = {"NoSuchKey", "NoSuchBucket", "NotFound", "404"}


class PathTraversalError(ValueError):
    """The requested path is absolute, contains '..' or leaves the project."""


def normalize_project_id(project_id: str) -> str:
    """Reject project ids that are not a single safe path segment."""
    value = str(project_id or "").strip().replace("\\", "/")
    if not value or "/" in value or value in (".", ".."):
        raise PathTraversalError("Invalid project id: {}".format(project_id))
    return value


def normalize_relative_path(file_path: str) -> str:
    """Return a safe POSIX relative path, or raise PathTraversalError."""
    raw = str(file_path or "").strip().replace("\\", "/")
    if not raw or raw == ".":
        return ""

    # Absolute paths (POSIX, Windows drive or UNC) are never valid here.
    if raw.startswith("/") or (len(raw) > 1 and raw[1] == ":"):
        raise PathTraversalError("Absolute paths are not allowed: {}".format(file_path))
    if ".." in raw.split("/"):
        raise PathTraversalError("Path traversal attempt blocked: {}".format(file_path))

    normalized = posixpath.normpath(raw)
    if normalized.startswith("/") or normalized == ".." or normalized.startswith("../"):
        raise PathTraversalError("Path traversal attempt blocked: {}".format(file_path))
    return normalized


def _normalize_prefix(prefix: str) -> str:
    value = str(prefix or "").strip().replace("\\", "/").strip("/")
    if not value:
        return ""
    return normalize_relative_path(value)


def _is_missing_object(exc: Exception) -> bool:
    """True when an S3 exception means the object simply does not exist."""
    if isinstance(exc, FileNotFoundError):
        return True
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False
    code = str(response.get("Error", {}).get("Code") or "")
    if code in _MISSING_OBJECT_CODES:
        return True
    return response.get("ResponseMetadata", {}).get("HTTPStatusCode") == 404


def _build_s3_client(region: Optional[str] = None):
    """Create an S3 client using boto3's default credential chain.

    Credentials are never passed explicitly: boto3 resolves them from the
    instance/task role, the shared config file or the AWS_ACCESS_KEY_ID /
    AWS_SECRET_ACCESS_KEY environment variables, in that order. boto3 is
    imported here so that local mode needs no AWS SDK at all.
    """
    import boto3

    if region:
        return boto3.client("s3", region_name=region)
    return boto3.client("s3")


class LocalStorage:
    """Project files on the shared local upload directory (the default)."""

    provider = "local"

    def __init__(self, upload_dir: str):
        self.upload_dir = upload_dir

    def project_root(self, project_id: str) -> Path:
        return (Path(self.upload_dir) / normalize_project_id(project_id)).resolve()

    def resolve(self, project_id: str, file_path: str = "") -> Path:
        """Resolve file_path inside the project directory, blocking traversal."""
        project_root = self.project_root(project_id)
        resolved = (project_root / normalize_relative_path(file_path)).resolve()

        # Note: must compare path components, not string prefixes,
        # otherwise allowing "project-1" would also allow "../project-10".
        if resolved != project_root and project_root not in resolved.parents:
            raise PathTraversalError("Path traversal attempt blocked")

        return resolved

    def is_file(self, project_id: str, file_path: str = "") -> bool:
        try:
            return self.resolve(project_id, file_path).is_file()
        except OSError:
            return False

    def read_text(self, project_id: str, file_path: str) -> str:
        path = self.resolve(project_id, file_path)
        if not path.is_file():
            raise FileNotFoundError("File not found: {}".format(file_path))
        with open(path, "r", encoding="utf-8", errors="ignore") as handle:
            return handle.read()

    def list_paths(self, project_id: str) -> List[str]:
        root = self.project_root(project_id)
        if not root.is_dir():
            return []
        return sorted(
            path.relative_to(root).as_posix()
            for path in root.rglob("*")
            if path.is_file()
        )


class S3Storage:
    """Project files in S3, laid out exactly as the backend writes them.

    Keys are ``<prefix>/<projectId>/<relativePath>``. The client is created on
    first use from boto3's default credential chain, and can be injected for
    tests so no AWS credentials are ever needed to exercise this class.
    """

    provider = "s3"

    def __init__(
            self,
            bucket: str,
            prefix: str = "projects",
            region: Optional[str] = None,
            client=None,
            client_factory=None):
        bucket = str(bucket or "").strip()
        if not bucket:
            raise ValueError("APP_S3_BUCKET must be set when APP_STORAGE_PROVIDER=s3")
        self.bucket = bucket
        self.prefix = _normalize_prefix(prefix)
        self.region = (region or "").strip() or None
        self._client = client
        self._client_factory = client_factory or _build_s3_client

    @property
    def client(self):
        if self._client is None:
            self._client = self._client_factory(self.region)
        return self._client

    def key_for(self, project_id: str, file_path: str = "") -> str:
        """The exact object key the backend wrote for this project file."""
        parts = [self.prefix, normalize_project_id(project_id)]
        relative = normalize_relative_path(file_path)
        if relative:
            parts.append(relative)
        return "/".join(part for part in parts if part)

    def is_file(self, project_id: str, file_path: str = "") -> bool:
        key = self.key_for(project_id, file_path)
        try:
            self.client.head_object(Bucket=self.bucket, Key=key)
            return True
        except Exception as exc:  # noqa: BLE001 - re-raised unless it means 404
            if _is_missing_object(exc):
                return False
            raise

    def read_text(self, project_id: str, file_path: str) -> str:
        key = self.key_for(project_id, file_path)
        try:
            body = self.client.get_object(Bucket=self.bucket, Key=key).get("Body")
        except Exception as exc:  # noqa: BLE001 - re-raised unless it means 404
            if _is_missing_object(exc):
                raise FileNotFoundError("File not found: {}".format(file_path)) from exc
            raise
        if body is None:
            raise FileNotFoundError("File not found: {}".format(file_path))
        return body.read().decode("utf-8", errors="ignore")

    def list_paths(self, project_id: str) -> List[str]:
        base = self.key_for(project_id) + "/"
        paths: List[str] = []
        token = None
        while True:
            request = {"Bucket": self.bucket, "Prefix": base}
            if token:
                request["ContinuationToken"] = token
            response = self.client.list_objects_v2(**request)
            for item in response.get("Contents") or []:
                key = str(item.get("Key") or "")
                # Sub-prefixes show up as "…/" keys; they are not files.
                if key.startswith(base) and not key.endswith("/"):
                    paths.append(key[len(base):])
            if response.get("IsTruncated") and response.get("NextContinuationToken"):
                token = response["NextContinuationToken"]
            else:
                break
        return sorted(paths)


def create_storage(upload_dir: str = "", env: Optional[Dict[str, str]] = None):
    """Build the provider named by APP_STORAGE_PROVIDER, defaulting to local."""
    values = os.environ if env is None else env
    provider = str(values.get("APP_STORAGE_PROVIDER") or "local").strip().lower()

    if provider == "local":
        return LocalStorage(values.get("UPLOAD_DIR") or upload_dir or "./uploads")

    if provider == "s3":
        # Credentials come from boto3's own chain, so only the bucket, prefix
        # and region are read here. APP_S3_REGION wins, then AWS_REGION.
        return S3Storage(
            bucket=values.get("APP_S3_BUCKET") or "",
            prefix=values.get("APP_S3_PREFIX") or "projects",
            region=values.get("APP_S3_REGION") or values.get("AWS_REGION"),
        )

    raise ValueError(
        "Unknown APP_STORAGE_PROVIDER: {!r} (expected 'local' or 's3')".format(provider)
    )
