import re
from pathlib import Path
from typing import Any, Dict, List, Optional

import psycopg2
from psycopg2.extras import RealDictCursor


class AgentTools:
    """Tools used by the CodebaseAgent to investigate a project.

    All tools are scoped to a single project_id and never trust raw
    filesystem paths from the LLM (path traversal is blocked).
    """

    def __init__(self, db_config: Dict[str, str], embedding_service, upload_dir: str):
        self.db_config = db_config
        self.embedding_service = embedding_service
        self.upload_dir = upload_dir

    def _get_db_connection(self):
        return psycopg2.connect(**self.db_config)

    def _safe_project_path(self, project_id: str, file_path: str = "") -> Path:
        """Resolve file_path inside the project directory, blocking traversal."""
        project_root = (Path(self.upload_dir) / project_id).resolve()
        resolved = (project_root / file_path).resolve()

        # Note: must compare path components, not string prefixes,
        # otherwise allowing "project-1" would also allow "../project-10".
        if resolved != project_root and project_root not in resolved.parents:
            raise ValueError("Path traversal attempt blocked")

        return resolved

    def semantic_search(self, query: str, project_id: str, limit: int = 5) -> List[Dict]:
        """Search code chunks by semantic similarity to the query."""
        query_embedding = self.embedding_service.generate_embeddings([query])[0]
        # pgvector accepts text literals of the form '[0.1,0.2,...]'
        query_vector = "[" + ",".join(str(x) for x in query_embedding) + "]"

        conn = self._get_db_connection()
        try:
            cur = conn.cursor(cursor_factory=RealDictCursor)
            cur.execute(
                """
                SELECT
                    c.id as chunk_id,
                    c.content,
                    c.start_line,
                    c.end_line,
                    f.path as file_path,
                    f.id as file_id,
                    1 - (c.embedding <=> %s::vector) as similarity
                FROM chunks c
                JOIN files f ON c.file_id = f.id
                WHERE c.project_id = %s
                ORDER BY c.embedding <=> %s::vector
                LIMIT %s
                """,
                (query_vector, project_id, query_vector, limit),
            )
            results = cur.fetchall()
            cur.close()
        finally:
            conn.close()

        return [
            {
                "chunk_id": str(r["chunk_id"]),
                "file_path": r["file_path"],
                "file_id": str(r["file_id"]),
                "content": r["content"],
                "start_line": r["start_line"],
                "end_line": r["end_line"],
                "similarity": float(r["similarity"]),
            }
            for r in results
        ]

    def read_file(self, file_path: str, project_id: str, start_line: Optional[int] = None,
                  end_line: Optional[int] = None) -> Dict[str, Any]:
        """Read file content, optionally a specific line range."""
        try:
            safe_path = self._safe_project_path(project_id, file_path)

            if not safe_path.exists():
                return {"error": f"File not found: {file_path}"}

            if not safe_path.is_file():
                return {"error": f"Not a file: {file_path}"}

            with open(safe_path, "r", encoding="utf-8", errors="ignore") as f:
                lines = f.readlines()

            if start_line is not None and end_line is not None:
                selected_lines = lines[start_line - 1:end_line]
                content = "".join(selected_lines)
                return {
                    "file_path": file_path,
                    "content": content,
                    "start_line": start_line,
                    "end_line": end_line,
                    "total_lines": len(lines),
                    "partial": True,
                }

            content = "".join(lines)
            return {
                "file_path": file_path,
                "content": content,
                "total_lines": len(lines),
                "partial": False,
            }

        except ValueError as e:
            return {"error": str(e)}
        except Exception as e:
            return {"error": f"Failed to read file: {e}"}

    def list_files(self, project_id: str) -> List[str]:
        """List all indexed files in the project."""
        conn = self._get_db_connection()
        try:
            cur = conn.cursor()
            cur.execute(
                "SELECT path FROM files WHERE project_id = %s ORDER BY path",
                (project_id,),
            )
            files = [row[0] for row in cur.fetchall()]
            cur.close()
        finally:
            conn.close()

        return files

    def find_dependencies(self, file_path: str, project_id: str) -> Dict[str, Any]:
        """Find imports in a file and other project files that reference its name."""
        try:
            safe_path = self._safe_project_path(project_id, file_path)

            if not safe_path.exists():
                return {"error": f"File not found: {file_path}"}

            with open(safe_path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()

            dependencies: Dict[str, Any] = {"imports": [], "referenced_by": []}

            # Simple import detection (works for multiple languages)
            import_patterns = [
                r"import\s+[\w.]+",                          # Java, Python
                r"from\s+[\w.]+\s+import",                   # Python
                r"require\([\'\"][^\'\"]+[\'\"]\)",          # JavaScript (CommonJS)
                r"import\s+.*?from\s+[\'\"][^\'\"]+[\'\"]",  # ES6
            ]

            for pattern in import_patterns:
                dependencies["imports"].extend(re.findall(pattern, content, re.MULTILINE))

            # Find other files whose indexed code mentions this file's base name
            base_name = Path(file_path).name
            escaped = base_name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

            conn = self._get_db_connection()
            try:
                cur = conn.cursor()
                cur.execute(
                    """
                    SELECT DISTINCT f.path
                    FROM chunks c
                    JOIN files f ON c.file_id = f.id
                    WHERE c.project_id = %s
                      AND f.path != %s
                      AND c.content LIKE '%%' || %s || '%%'
                    LIMIT 20
                    """,
                    (project_id, file_path, escaped),
                )
                dependencies["referenced_by"] = [row[0] for row in cur.fetchall()]
                cur.close()
            finally:
                conn.close()

            return dependencies

        except ValueError as e:
            return {"error": str(e)}
        except Exception as e:
            return {"error": f"Failed to find dependencies: {e}"}
