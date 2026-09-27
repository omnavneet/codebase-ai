import os
import posixpath
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

    def semantic_search(self, query: str, project_id: str, limit: Optional[int] = None) -> List[Dict]:
        """Search code chunks by semantic similarity to the query."""
        if limit is None:
            limit = int(os.getenv("AGENT_SEARCH_LIMIT", "5"))
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
        """Real import and call edges for a file, taken from the symbol index.

        Imports are resolved to project files where that is possible; external or
        unresolvable targets are reported as such instead of being guessed.
        """
        try:
            safe_path = self._safe_project_path(project_id, file_path)

            if not safe_path.exists():
                return {"error": f"File not found: {file_path}"}

            files = self._query_rows(
                "SELECT id::text AS id, path, language FROM files WHERE project_id = %s ORDER BY path",
                (project_id,),
            )
            target = next((item for item in files if item["path"] == file_path), None)
            if target is None:
                return {"error": f"File not indexed: {file_path}", "imports": [], "referenced_by": []}

            project_paths = [item["path"] for item in files]
            import_rows = self._query_rows(
                """
                SELECT r.to_name, r.line
                FROM code_references r
                WHERE r.file_id = %s AND r.kind = 'import'
                ORDER BY r.line
                """,
                (target["id"],),
            )

            imports = []
            unresolved = 0
            for row in import_rows:
                target_name = str(row["to_name"])
                resolved = resolve_import(target_name, file_path, target["language"], project_paths)
                if resolved is None:
                    unresolved += 1
                imports.append({
                    "target": target_name,
                    "line": int(row["line"]),
                    "resolved_path": resolved,
                    "external": resolved is None and is_external_import(target_name, target["language"]),
                })

            referenced_by = self._query_rows(
                """
                SELECT DISTINCT f2.path
                FROM code_references r
                JOIN files f2 ON r.file_id = f2.id
                WHERE r.project_id = %s AND r.kind = 'call' AND r.file_id <> %s
                  AND EXISTS (
                      SELECT 1 FROM code_symbols s
                      WHERE s.project_id = %s AND s.file_id = %s
                        AND LOWER(s.name) = LOWER(split_part(r.to_name, '.', -1)))
                LIMIT 20
                """,
                (project_id, target["id"], project_id, target["id"]),
            )

            dependencies: Dict[str, Any] = {
                "imports": imports,
                "referenced_by": [row["path"] for row in referenced_by],
                "language": target["language"] or "unknown",
            }
            if unresolved:
                dependencies["notes"] = [
                    "{} import(s) could not be resolved to a project file".format(unresolved)
                ]

            return dependencies

        except ValueError as e:
            return {"error": str(e)}
        except Exception as e:
            return {"error": f"Failed to find dependencies: {e}"}

    # ------------------------------------------------------------------
    # Symbol index and call graph (migration V10 tables)
    # ------------------------------------------------------------------

    def _query_rows(self, sql: str, params) -> List[Dict[str, Any]]:
        conn = self._get_db_connection()
        try:
            cur = conn.cursor(cursor_factory=RealDictCursor)
            cur.execute(sql, params)
            rows = cur.fetchall()
            cur.close()
        finally:
            conn.close()
        return [dict(row) for row in rows]

    def project_symbols(self, project_id: str) -> List[Dict[str, Any]]:
        rows = self._query_rows(
            """
            SELECT s.name, s.kind, s.parent_symbol, s.start_line, s.end_line,
                   s.signature, f.path AS file_path, s.file_id
            FROM code_symbols s
            JOIN files f ON s.file_id = f.id
            WHERE s.project_id = %s
            ORDER BY f.path, s.start_line
            """,
            (project_id,),
        )
        for row in rows:
            row["file_id"] = str(row["file_id"])
        return rows

    def analyze_symbol(self, name: str, project_id: str, file_path: Optional[str] = None) -> Dict[str, Any]:
        """Deep-dive one declared symbol: its source, who calls it, what it calls.

        Honest by construction: candidates are real declarations from the symbol
        index, call edges are resolved against it, and anything dynamic or
        unresolved is reported in `notes` instead of being dropped or invented.
        """
        name = (name or "").strip()
        if not name:
            return {"error": "Symbol name is required"}

        symbols = self.project_symbols(project_id)
        index: Dict[str, List[Dict[str, Any]]] = {}
        for symbol in symbols:
            index.setdefault(str(symbol["name"]).lower(), []).append(symbol)

        candidates = index.get(name.lower(), [])
        if not candidates:
            hints = sorted({s["name"] for s in symbols if name.lower() in str(s["name"]).lower()})[:5]
            return {
                "name": name,
                "found": False,
                "candidates": [],
                "similar_names": hints,
                "callers": [],
                "callees": [],
                "notes": ["No declared symbol with this name; use semantic_search for concept-level search"],
            }

        if file_path:
            scoped = [c for c in candidates if str(c["file_path"]) == file_path]
            if scoped:
                candidates = scoped

        callers = self._callers_of(project_id, name)
        callees: List[Dict[str, Any]] = []
        for candidate in candidates[:3]:
            callees.extend(self._callees_of(project_id, candidate, symbols))

        notes = []
        dynamic = [c for c in callers if c.get("dynamic")]
        if dynamic:
            notes.append("{} call site(s) are dynamic and could not be resolved statically".format(len(dynamic)))
        unresolved = [c for c in callees if not c.get("resolved")]
        if unresolved:
            notes.append("{} callee(s) did not match any declared symbol".format(len(unresolved)))
        if len(candidates) > 1:
            notes.append("Multiple declarations share this name; check the file paths")

        return {
            "name": name,
            "found": True,
            "candidates": [
                {
                    "name": candidate["name"],
                    "kind": candidate["kind"],
                    "parent_symbol": candidate.get("parent_symbol"),
                    "file_path": candidate["file_path"],
                    "start_line": int(candidate["start_line"]),
                    "end_line": int(candidate["end_line"]),
                    "signature": candidate.get("signature"),
                }
                for candidate in candidates
            ],
            "selected_source": self._source_of(candidates[0], project_id),
            "callers": callers,
            "callees": callees,
            "notes": notes,
        }

    def _source_of(self, candidate: Dict[str, Any], project_id: str) -> Optional[Dict[str, Any]]:
        start = max(1, int(candidate["start_line"]))
        end = min(start + 400, int(candidate["end_line"]))
        source = self.read_file(candidate["file_path"], project_id, start, end)
        return None if "error" in source else source

    def _callers_of(self, project_id: str, name: str) -> List[Dict[str, Any]]:
        rows = self._query_rows(
            """
            SELECT r.from_symbol, r.to_name, r.line, r.is_dynamic, f.path AS file_path
            FROM code_references r
            JOIN files f ON r.file_id = f.id
            WHERE r.project_id = %s AND r.kind = 'call'
              AND LOWER(split_part(r.to_name, '.', -1)) = LOWER(%s)
            ORDER BY f.path, r.line
            LIMIT 50
            """,
            (project_id, name),
        )
        return [
            {
                "file_path": row["file_path"],
                "line": int(row["line"]),
                "from_symbol": row.get("from_symbol"),
                "call": row["to_name"],
                "dynamic": bool(row.get("is_dynamic")),
            }
            for row in rows
        ]

    def _callees_of(
            self, project_id: str, candidate: Dict[str, Any], symbols: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        rows = self._query_rows(
            """
            SELECT r.to_name, r.line, r.is_dynamic
            FROM code_references r
            WHERE r.project_id = %s AND r.kind = 'call' AND r.file_id = %s
              AND r.line BETWEEN %s AND %s
            ORDER BY r.line
            LIMIT 60
            """,
            (project_id, candidate["file_id"], candidate["start_line"], candidate["end_line"]),
        )
        declared: Dict[str, int] = {}
        for symbol in symbols:
            key = str(symbol["name"]).lower()
            declared[key] = declared.get(key, 0) + 1

        result = []
        for row in rows:
            simple = str(row["to_name"]).split(".")[-1].lower()
            count = declared.get(simple, 0)
            result.append({
                "to_name": row["to_name"],
                "line": int(row["line"]),
                "resolved": count > 0,
                "candidates": count,
                "dynamic": bool(row.get("is_dynamic")),
            })
        return result


def resolve_import(target: str, importer_path: str, language, paths: List[str]) -> Optional[str]:
    """Best-effort mapping of an import target to a project file.

    Returns None when the target cannot be matched; callers treat that as
    unresolved (an external package or a dynamic import) rather than guessing.
    """
    if not target:
        return None
    normalised = [path.replace("\\", "/") for path in paths]

    if language == "java":
        if target.endswith(".*"):
            prefix = target[:-2].replace(".", "/") + "/"
            for path in normalised:
                if path.startswith(prefix):
                    return path
            return None
        candidate = target.split("$")[0].replace(".", "/") + ".java"
        for path in normalised:
            if path.endswith(candidate):
                return path
        return None

    if language == "python":
        module = target
        level = 0
        while module.startswith("."):
            level += 1
            module = module[1:]
        parts = [part for part in module.split(".") if part]
        base = "/".join(parts)
        if level > 0:
            directory = posixpath.dirname(importer_path.replace("\\", "/"))
            base = posixpath.normpath(
                posixpath.join(directory, *([".."] * (level - 1)), base)) if parts else directory
        for candidate in [base + ".py", posixpath.join(base, "__init__.py")]:
            for path in normalised:
                if path == candidate or path.endswith("/" + candidate):
                    return path
        return None

    if language in ("javascript", "typescript", "tsx"):
        if not target.startswith("."):
            return None  # bare specifier: an npm package, not a project file
        directory = posixpath.dirname(importer_path.replace("\\", "/"))
        base = posixpath.normpath(posixpath.join(directory, target))
        extensions = ["", ".js", ".ts", ".jsx", ".tsx", ".mjs", ".cjs"]
        candidates = [base + extension for extension in extensions]
        candidates += [posixpath.join(base, "index" + extension) for extension in extensions[1:]]
        for candidate in candidates:
            for path in normalised:
                if path == candidate:
                    return path
        return None

    return None


def is_external_import(target: str, language) -> bool:
    """True when an unresolvable import is clearly outside the project."""
    if language in ("javascript", "typescript", "tsx"):
        return not target.startswith(".")
    return False
