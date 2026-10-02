"""Unit tests for the project storage providers and the tools built on them.

Nothing here touches AWS: ``S3Storage`` accepts an injected client, so a small
in-memory stand-in exercises every path (key layout, pagination, missing
objects) without credentials, a bucket or the network.
"""

import sys
import tempfile
import types
import unittest
from pathlib import Path

# agent_tools imports psycopg2 at module scope. Stub it so this module runs with
# a bare interpreter, exactly as test_core_safety does.
psycopg2_stub = types.ModuleType("psycopg2")
psycopg2_extras_stub = types.ModuleType("psycopg2.extras")
psycopg2_extras_stub.RealDictCursor = object
psycopg2_stub.extras = psycopg2_extras_stub
sys.modules.setdefault("psycopg2", psycopg2_stub)
sys.modules.setdefault("psycopg2.extras", psycopg2_extras_stub)

from agent_tools import AgentTools
from storage import (
    LocalStorage,
    PathTraversalError,
    S3Storage,
    create_storage,
    normalize_project_id,
    normalize_relative_path,
)


class FakeBody:
    def __init__(self, data):
        self._data = data

    def read(self):
        return self._data


class FakeS3Error(Exception):
    """Shaped like a botocore ClientError, which is all storage.py inspects."""

    def __init__(self, code, status):
        super().__init__("{} ({})".format(code, status))
        self.code = code
        self.status = status

    @property
    def response(self):
        return {
            "Error": {"Code": self.code},
            "ResponseMetadata": {"HTTPStatusCode": self.status},
        }


class FakeS3Client:
    """Minimal stand-in for boto3's S3 client over a dict of key -> bytes."""

    def __init__(self, objects=None, page_size=None, failures=None):
        self.objects = {
            key: value.encode("utf-8") if isinstance(value, str) else value
            for key, value in (objects or {}).items()
        }
        self.page_size = page_size
        # Method name -> exception to raise, for error-path tests.
        self.failures = dict(failures or {})
        self.calls = []

    def _maybe_fail(self, method):
        if method in self.failures:
            raise self.failures[method]

    def head_object(self, Bucket, Key):
        self.calls.append(("head_object", Bucket, Key))
        self._maybe_fail("head_object")
        if Key not in self.objects:
            raise FakeS3Error("404", 404)
        return {"ContentLength": len(self.objects[Key])}

    def get_object(self, Bucket, Key):
        self.calls.append(("get_object", Bucket, Key))
        self._maybe_fail("get_object")
        if Key not in self.objects:
            raise FakeS3Error("NoSuchKey", 404)
        return {"Body": FakeBody(self.objects[Key])}

    def list_objects_v2(self, Bucket, Prefix, ContinuationToken=None):
        self.calls.append(("list_objects_v2", Bucket, Prefix))
        self._maybe_fail("list_objects_v2")
        keys = sorted(key for key in self.objects if key.startswith(Prefix))
        next_token = None
        if self.page_size:
            start = int(ContinuationToken or 0)
            keys, remainder = keys[start:start + self.page_size], keys[start + self.page_size:]
            if remainder:
                next_token = str(start + self.page_size)
        response = {
            "Contents": [{"Key": key, "Size": len(self.objects[key])} for key in keys],
            "IsTruncated": next_token is not None,
        }
        if next_token is not None:
            response["NextContinuationToken"] = next_token
        return response

    def keys_touched(self):
        return [call[-1] for call in self.calls]


class _EmptyCursor:
    def execute(self, *args, **kwargs):
        return None

    def fetchall(self):
        return []

    def close(self):
        return None


class _EmptyConnection:
    """Stands in for a database connection that has no indexed files."""

    def cursor(self, *args, **kwargs):
        return _EmptyCursor()

    def close(self):
        return None


class PathValidationTests(unittest.TestCase):
    def test_rejects_absolute_paths_of_every_flavour(self):
        for path in ("/etc/passwd", "C:/Windows/system32", "\\\\server\\share\\file", "//etc/passwd"):
            with self.subTest(path=path):
                with self.assertRaises(PathTraversalError):
                    normalize_relative_path(path)

    def test_rejects_parent_traversal(self):
        for path in ("..", "../outside.txt", "src/../../outside.txt", "src/.."):
            with self.subTest(path=path):
                with self.assertRaises(PathTraversalError):
                    normalize_relative_path(path)

    def test_normalizes_separators_and_dot_segments(self):
        self.assertEqual("src/main/Foo.java", normalize_relative_path("src\\main\\.\\Foo.java"))
        self.assertEqual("src/main/Foo.java", normalize_relative_path("  src/main/Foo.java  "))
        self.assertEqual("", normalize_relative_path(""))
        self.assertEqual("", normalize_relative_path("."))

    def test_project_id_must_be_a_single_segment(self):
        self.assertEqual("project-1", normalize_project_id(" project-1 "))
        for project_id in ("", "..", ".", "a/b", "a\\b", None):
            with self.subTest(project_id=project_id):
                with self.assertRaises(PathTraversalError):
                    normalize_project_id(project_id)


class LocalStorageTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.upload_dir = self._tmp.name

        self.project = Path(self.upload_dir) / "project-1"
        (self.project / "src" / "main").mkdir(parents=True)
        (self.project / "src" / "main" / "Foo.java").write_text("class Foo {}\n", encoding="utf-8")
        (self.project / "README.md").write_text("# project\n", encoding="utf-8")
        (Path(self.upload_dir) / "project-2").mkdir()
        (Path(self.upload_dir) / "project-2" / "secret.txt").write_text("nope\n", encoding="utf-8")

        self.storage = LocalStorage(self.upload_dir)

    def test_lists_files_relative_to_the_project_root(self):
        self.assertEqual(
            ["README.md", "src/main/Foo.java"],
            self.storage.list_paths("project-1"),
        )

    def test_reads_file_text(self):
        self.assertEqual("class Foo {}\n", self.storage.read_text("project-1", "src/main/Foo.java"))

    def test_missing_file_raises_filenotfound(self):
        with self.assertRaises(FileNotFoundError):
            self.storage.read_text("project-1", "src/missing.java")

    def test_is_file_answers_for_files_and_directories(self):
        self.assertTrue(self.storage.is_file("project-1", "README.md"))
        self.assertFalse(self.storage.is_file("project-1", "src"))
        self.assertFalse(self.storage.is_file("project-1", "missing.txt"))

    def test_blocks_traversal_into_another_project(self):
        with self.assertRaises(PathTraversalError):
            self.storage.read_text("project-1", "../project-2/secret.txt")
        with self.assertRaises(PathTraversalError):
            self.storage.resolve("project-1", "/etc/passwd")

    def test_unknown_project_lists_nothing(self):
        self.assertEqual([], self.storage.list_paths("does-not-exist"))


class S3StorageKeyTests(unittest.TestCase):
    def test_key_includes_prefix_project_and_path(self):
        storage = S3Storage("my-bucket", client=FakeS3Client())
        self.assertEqual("projects/project-1/src/main/Foo.java",
                         storage.key_for("project-1", "src/main/Foo.java"))

    def test_key_for_the_project_root_has_no_trailing_slash(self):
        storage = S3Storage("my-bucket", client=FakeS3Client())
        self.assertEqual("projects/project-1", storage.key_for("project-1"))

    def test_prefix_is_normalized_and_can_be_omitted(self):
        self.assertEqual("projects/project-1/a.txt",
                         S3Storage("b", prefix="/projects/", client=FakeS3Client())
                         .key_for("project-1", "a.txt"))
        self.assertEqual("project-1/a.txt",
                         S3Storage("b", prefix="", client=FakeS3Client())
                         .key_for("project-1", "a.txt"))

    def test_traversal_is_rejected_before_the_client_is_used(self):
        client = FakeS3Client()
        storage = S3Storage("my-bucket", client=client)
        with self.assertRaises(PathTraversalError):
            storage.key_for("project-1", "/etc/passwd")
        with self.assertRaises(PathTraversalError):
            storage.key_for("project-1", "../project-2/secret.txt")
        with self.assertRaises(PathTraversalError):
            storage.key_for("project-1/../project-2", "secret.txt")
        self.assertEqual([], client.calls)

    def test_blank_bucket_is_rejected(self):
        for bucket in ("", "   ", None):
            with self.subTest(bucket=bucket):
                with self.assertRaises(ValueError):
                    S3Storage(bucket)


class S3StorageReadTests(unittest.TestCase):
    def setUp(self):
        self.objects = {"projects/project-1/src/main/Foo.java": "class Foo {}\n"}

    def _storage(self, **kwargs):
        client = FakeS3Client(self.objects, **kwargs)
        return S3Storage("my-bucket", client=client), client

    def test_reads_the_object_decoded_as_utf8(self):
        storage, _ = self._storage()
        self.assertEqual("class Foo {}\n", storage.read_text("project-1", "src/main/Foo.java"))

    def test_missing_object_maps_to_filenotfound(self):
        storage, _ = self._storage()
        with self.assertRaises(FileNotFoundError):
            storage.read_text("project-1", "src/missing.java")

    def test_a_bare_404_also_counts_as_missing(self):
        storage, _ = self._storage(failures={"get_object": FakeS3Error("Weird", 404)})
        with self.assertRaises(FileNotFoundError):
            storage.read_text("project-1", "src/main/Foo.java")

    def test_other_errors_are_not_swallowed(self):
        storage, _ = self._storage(failures={"get_object": FakeS3Error("AccessDenied", 403)})
        with self.assertRaises(FakeS3Error):
            storage.read_text("project-1", "src/main/Foo.java")

    def test_is_file_reports_presence(self):
        storage, _ = self._storage()
        self.assertTrue(storage.is_file("project-1", "src/main/Foo.java"))
        self.assertFalse(storage.is_file("project-1", "src/missing.java"))

    def test_is_file_propagates_permission_errors(self):
        storage, _ = self._storage(failures={"head_object": FakeS3Error("AccessDenied", 403)})
        with self.assertRaises(FakeS3Error):
            storage.is_file("project-1", "src/main/Foo.java")


class S3StorageListTests(unittest.TestCase):
    def test_lists_only_files_under_the_project_and_strips_the_prefix(self):
        storage = S3Storage("my-bucket", client=FakeS3Client({
            "projects/project-1/README.md": "# project\n",
            "projects/project-1/src/main/Foo.java": "class Foo {}\n",
            "projects/project-1/src/": "",
            "projects/project-2/secret.txt": "nope\n",
            "other/notes.txt": "unrelated\n",
        }))
        self.assertEqual(["README.md", "src/main/Foo.java"], storage.list_paths("project-1"))

    def test_follows_continuation_tokens(self):
        storage = S3Storage("my-bucket", client=FakeS3Client({
            "projects/project-1/a.txt": "a\n",
            "projects/project-1/b.txt": "b\n",
            "projects/project-1/c.txt": "c\n",
        }, page_size=2))
        self.assertEqual(["a.txt", "b.txt", "c.txt"], storage.list_paths("project-1"))

    def test_unknown_project_lists_nothing(self):
        storage = S3Storage("my-bucket", client=FakeS3Client({
            "projects/project-1/a.txt": "a\n",
        }))
        self.assertEqual([], storage.list_paths("project-2"))


class S3StorageClientTests(unittest.TestCase):
    def test_client_is_built_lazily_from_the_factory_with_the_region(self):
        created = []

        def factory(region):
            created.append(region)
            return FakeS3Client()

        storage = S3Storage("my-bucket", region="eu-west-1", client_factory=factory)
        self.assertEqual([], created)  # nothing is created until it is needed

        storage.is_file("project-1", "a.txt")
        self.assertEqual(["eu-west-1"], created)

    def test_an_injected_client_is_used_and_the_factory_is_never_called(self):
        def factory(region):  # pragma: no cover - failing here is the assertion
            raise AssertionError("the factory must not be called")

        client = FakeS3Client({"projects/project-1/a.txt": "a\n"})
        storage = S3Storage("my-bucket", client=client, client_factory=factory)
        self.assertTrue(storage.is_file("project-1", "a.txt"))
        self.assertEqual("projects/project-1/a.txt", client.keys_touched()[0])


class CreateStorageTests(unittest.TestCase):
    def test_defaults_to_local_storage(self):
        storage = create_storage("/tmp/uploads", env={})
        self.assertIsInstance(storage, LocalStorage)
        self.assertEqual("local", storage.provider)
        self.assertEqual("/tmp/uploads", storage.upload_dir)

    def test_upload_dir_env_wins_over_the_argument(self):
        storage = create_storage("/tmp/uploads", env={"UPLOAD_DIR": "/srv/uploads"})
        self.assertEqual("/srv/uploads", storage.upload_dir)

    def test_provider_name_is_case_and_whitespace_insensitive(self):
        storage = create_storage("", env={"APP_STORAGE_PROVIDER": " S3 ", "APP_S3_BUCKET": "b"})
        self.assertIsInstance(storage, S3Storage)

    def test_s3_provider_reads_bucket_prefix_and_region(self):
        storage = create_storage("", env={
            "APP_STORAGE_PROVIDER": "s3",
            "APP_S3_BUCKET": "my-bucket",
            "APP_S3_PREFIX": "code/",
            "APP_S3_REGION": "ap-south-1",
        })
        self.assertEqual("my-bucket", storage.bucket)
        self.assertEqual("code", storage.prefix)
        self.assertEqual("ap-south-1", storage.region)
        self.assertEqual("code/project-1/a.txt", storage.key_for("project-1", "a.txt"))

    def test_s3_provider_defaults_prefix_and_falls_back_to_aws_region(self):
        storage = create_storage("", env={
            "APP_STORAGE_PROVIDER": "s3",
            "APP_S3_BUCKET": "my-bucket",
            "AWS_REGION": "us-east-1",
        })
        self.assertEqual("projects", storage.prefix)
        self.assertEqual("us-east-1", storage.region)

    def test_s3_provider_without_a_bucket_fails_fast(self):
        with self.assertRaises(ValueError):
            create_storage("", env={"APP_STORAGE_PROVIDER": "s3"})

    def test_unknown_provider_is_rejected(self):
        with self.assertRaises(ValueError):
            create_storage("", env={"APP_STORAGE_PROVIDER": "gcs"})


class AgentToolStorageTests(unittest.TestCase):
    """The tools must work through the storage provider, not the raw filesystem."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.upload_dir = self._tmp.name

        project = Path(self.upload_dir) / "project-1"
        (project / "src" / "main").mkdir(parents=True)
        (project / "src" / "main" / "Foo.java").write_text(
            "class Foo {\n    void bar() {}\n}\n", encoding="utf-8")
        (project / "README.md").write_text("# project\n", encoding="utf-8")

        self.tools = AgentTools({}, None, self.upload_dir)

    def _s3_tools(self, objects):
        """Tools backed by an injected S3 client, i.e. no local files at all."""
        return AgentTools(
            {}, None, self.upload_dir,
            storage=S3Storage("my-bucket", client=FakeS3Client(objects)),
        )

    def test_read_file_returns_content_through_the_provider(self):
        result = self.tools.read_file("README.md", "project-1")
        self.assertEqual("# project\n", result["content"])
        self.assertFalse(result["partial"])

    def test_read_file_blocks_traversal(self):
        result = self.tools.read_file("../outside.txt", "project-1")
        self.assertIn("error", result)

    def test_read_file_reports_missing_files(self):
        result = self.tools.read_file("src/missing.java", "project-1")
        self.assertEqual("File not found: src/missing.java", result["error"])

    def test_list_files_falls_back_to_storage_when_nothing_is_indexed(self):
        self.tools._get_db_connection = lambda: _EmptyConnection()
        self.assertEqual(["README.md", "src/main/Foo.java"], self.tools.list_files("project-1"))

    def test_grep_finds_matches_across_the_project(self):
        result = self.tools.grep("bar\\s*\\(", "project-1")
        self.assertEqual(2, result["files_scanned"])
        self.assertEqual(1, len(result["matches"]))
        match = result["matches"][0]
        self.assertEqual("src/main/Foo.java", match["file_path"])
        self.assertEqual(2, match["line"])
        self.assertIn("void bar()", match["text"])

    def test_grep_reads_only_the_named_file(self):
        result = self.tools.grep("project", "project-1", file_path="README.md")
        self.assertEqual("README.md", result["file_path"])
        self.assertEqual(1, result["files_scanned"])
        self.assertEqual(1, len(result["matches"]))

    def test_grep_bounds_the_number_of_matches(self):
        result = self.tools.grep(".", "project-1", max_matches=2)
        self.assertEqual(2, len(result["matches"]))
        self.assertTrue(any("first 2 matches" in note for note in result["notes"]))

    def test_grep_bounds_the_number_of_files_scanned(self):
        original = AgentTools.MAX_GREP_FILES
        AgentTools.MAX_GREP_FILES = 1
        self.addCleanup(setattr, AgentTools, "MAX_GREP_FILES", original)

        result = self.tools.grep("e", "project-1")
        self.assertEqual(1, result["files_scanned"])
        self.assertTrue(any("first 1 files" in note for note in result["notes"]))

    def test_grep_rejects_a_bad_pattern(self):
        self.assertTrue(self.tools.grep("(", "project-1")["error"].startswith("Invalid regular expression"))
        self.assertEqual("pattern is required", self.tools.grep("", "project-1")["error"])

    def test_grep_blocks_traversal(self):
        result = self.tools.grep("secret", "project-1", file_path="../project-2/secret.txt")
        self.assertIn("error", result)

    def test_grep_works_against_s3_backed_storage(self):
        tools = self._s3_tools({
            "projects/project-1/src/app.py": "import os\nprint('hi')\n",
            "projects/project-1/src/util.py": "import sys\n",
        })
        result = tools.grep("import", "project-1")
        self.assertEqual(2, result["files_scanned"])
        self.assertEqual(["src/app.py", "src/util.py"],
                         [match["file_path"] for match in result["matches"]])

    def test_read_file_works_against_s3_backed_storage(self):
        tools = self._s3_tools({"projects/project-1/README.md": "# from s3\n"})
        self.assertEqual("# from s3\n", tools.read_file("README.md", "project-1")["content"])


if __name__ == "__main__":
    unittest.main()