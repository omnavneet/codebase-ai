"""Opt-in PostgreSQL test for published-generation query isolation.

Set AI_TEST_POSTGRES_DSN to a disposable pgvector-enabled database.
"""

import os
import uuid

import psycopg2
import pytest

from agent_tools import AgentTools


DSN = os.getenv("AI_TEST_POSTGRES_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="AI_TEST_POSTGRES_DSN is not configured")


@pytest.fixture
def database():
    from psycopg2 import sql
    schema = "generation_test_" + uuid.uuid4().hex
    connection = psycopg2.connect(DSN)
    connection.autocommit = True
    with connection.cursor() as cursor:
        cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
        cursor.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        cursor.execute(sql.SQL("SET search_path TO {}, public").format(sql.Identifier(schema)))
        cursor.execute("""
            CREATE TABLE projects (id uuid PRIMARY KEY, active_generation_id uuid);
            CREATE TABLE published_generations (
                project_id uuid NOT NULL, generation_id uuid NOT NULL,
                PRIMARY KEY (project_id, generation_id)
            );
            CREATE TABLE files (
                id uuid PRIMARY KEY, project_id uuid NOT NULL, generation_id uuid,
                path text NOT NULL, language text
            );
            CREATE TABLE chunks (
                id uuid PRIMARY KEY, project_id uuid NOT NULL, file_id uuid NOT NULL,
                content text, start_line int, end_line int, embedding vector(3)
            );
            CREATE TABLE code_symbols (
                project_id uuid NOT NULL, file_id uuid NOT NULL, name text, kind text,
                parent_symbol text, start_line int, end_line int, signature text
            );
            CREATE TABLE code_references (
                project_id uuid NOT NULL, file_id uuid NOT NULL, kind text,
                from_symbol text, to_name text, line int, is_dynamic boolean
            )
        """)
    yield connection, schema
    with connection.cursor() as cursor:
        cursor.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))
    connection.close()


def test_published_snapshots_isolate_search_symbols_and_dependencies(database, tmp_path):
    connection, schema = database
    project, old, new, staged = (str(uuid.uuid4()) for _ in range(4))
    file_ids = [str(uuid.uuid4()) for _ in range(3)]
    with connection.cursor() as cursor:
        cursor.execute("INSERT INTO projects VALUES (%s, %s)", (project, old))
        for generation in (old, new):
            cursor.execute("INSERT INTO published_generations VALUES (%s, %s)", (project, generation))
        for generation, file_id, name in zip((old, new, staged), file_ids, ("old", "new", "staged")):
            cursor.execute("INSERT INTO files VALUES (%s, %s, %s, %s, %s)",
                           (file_id, project, generation, "app.py", "python"))
            cursor.execute("INSERT INTO chunks VALUES (%s, %s, %s, %s, 1, 1, '[1,0,0]')",
                           (str(uuid.uuid4()), project, file_id, name))
            cursor.execute("INSERT INTO code_symbols VALUES (%s, %s, %s, 'function', null, 1, 1, null)",
                           (project, file_id, name))
            cursor.execute("INSERT INTO code_references VALUES (%s, %s, 'call', null, %s, 1, false)",
                           (project, file_id, name))
            directory = tmp_path / ".generations" / project / generation
            directory.mkdir(parents=True)
            (directory / "app.py").write_text(name + "\n", encoding="utf-8")

    embedding = type("Embeddings", (), {"generate_embeddings": lambda self, _: [[1, 0, 0]]})()
    tools = AgentTools({}, embedding, str(tmp_path))
    tools._get_db_connection = lambda **kwargs: psycopg2.connect(
        DSN, options=f"-csearch_path={schema},public", **kwargs)

    with tools.generation_scope(project):
        with connection.cursor() as cursor:
            cursor.execute("UPDATE projects SET active_generation_id = %s WHERE id = %s", (new, project))
        assert tools.list_files(project) == ["app.py"]
        assert tools.read_file("app.py", project)["content"] == "old\n"
        assert tools.source_file_ids(project, ["app.py"]) == {"app.py": file_ids[0]}
        assert [row["content"] for row in tools.semantic_search("query", project)] == ["old"]
        assert [row["name"] for row in tools.project_symbols(project)] == ["old"]
        assert tools.analyze_symbol("old", project)["callers"][0]["call"] == "old"
        assert tools.analyze_symbol("new", project)["found"] is False
    with tools.generation_scope(project, old):
        assert tools.read_file("app.py", project)["content"] == "old\n"
    with pytest.raises(ValueError, match="not published"):
        with tools.generation_scope(project, staged):
            pass
    with tools.generation_scope(project):
        assert tools.read_file("app.py", project)["content"] == "new\n"
        assert tools.source_file_ids(project, ["app.py"]) == {"app.py": file_ids[1]}
    assert tools.source_file_ids(project, ["app.py"], old) == {"app.py": file_ids[0]}
    assert tools.active_generation(project, timeout=5) == new

    with connection.cursor() as cursor:
        cursor.execute("BEGIN")
        try:
            cursor.execute("LOCK TABLE projects IN ACCESS EXCLUSIVE MODE")
            with pytest.raises(TimeoutError, match="generation lookup timed out"):
                tools.active_generation(project, timeout=0.25)
            cursor.execute("LOCK TABLE files IN ACCESS EXCLUSIVE MODE")
            with pytest.raises(TimeoutError, match="generation lookup timed out"):
                tools.source_file_ids(project, ["app.py"], old, timeout=0.25)
        finally:
            cursor.execute("ROLLBACK")
