import tempfile
import sys
import types
import unittest

# The path-safety test does not use the database. Keep it runnable with the
# standard interpreter even when the optional service dependencies are absent.
psycopg2_stub = types.ModuleType("psycopg2")
psycopg2_extras_stub = types.ModuleType("psycopg2.extras")
psycopg2_extras_stub.RealDictCursor = object
psycopg2_stub.extras = psycopg2_extras_stub
sys.modules.setdefault("psycopg2", psycopg2_stub)
sys.modules.setdefault("psycopg2.extras", psycopg2_extras_stub)

from agent_tools import AgentTools
from stack_trace_parser import parse_stack_trace, project_frames


class CoreSafetyTests(unittest.TestCase):
    def test_parses_python_trace_and_matches_project_file(self):
        trace = '''Traceback (most recent call last):
  File "app/services/auth.py", line 42, in login
    raise ValueError("bad")
ValueError: bad
'''

        parsed = parse_stack_trace(trace)
        matches = project_frames(parsed, ["app/services/auth.py", "app/main.py"])

        self.assertTrue(parsed["parsed"])
        self.assertEqual("python", parsed["language"])
        self.assertEqual(42, parsed["frames"][0]["line"])
        self.assertEqual("app/services/auth.py", matches[0]["project_path"])

    def test_read_file_blocks_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = AgentTools({}, None, directory)
            with self.assertRaises(ValueError):
                tools._safe_project_path("project-id", "../outside.txt")


if __name__ == "__main__":
    unittest.main()
