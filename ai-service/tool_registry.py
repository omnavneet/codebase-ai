"""Registry of the tools the agent is allowed to call.

Single source of truth for every tool surface: each entry carries the tool's
name, description, Pydantic input and output models, a permission level and
the handler that bridges to ``AgentTools``. Consumers generate from it:

* ``ToolSpec.groq_function`` builds the Groq function-calling schema straight
  from the input model, so the JSON the model sees can never drift from the
  code that validates it;
* ``mcp_server`` registers the same specs with the MCP SDK, so external MCP
  clients discover exactly what this registry declares.

Only ``read``-level tools exist today; the permission field is what the MCP
server checks before exposing a tool and what the agent uses to refuse
anything that could mutate state.
"""

import copy
from dataclasses import dataclass
from typing import Annotated, Any, Callable, Dict, List, Literal, Optional, Type

from pydantic import BaseModel, Field

Permission = Literal["read", "execute", "write", "destructive"]

# The agent already knows which project it is investigating, so project_id is
# injected server-side and never handed to the LLM as a function argument.
PROJECT_ID_FIELD = "project_id"


class ToolInvocationError(Exception):
    """A tool ran but reported a clean, user-facing failure (e.g. not found).

    Handlers raise this so the MCP layer can surface the message as a normal
    tool error the model can read, instead of a crash with a stack trace.
    """


# ---------------------------------------------------------------------------
# Input models (the contract callers validate against)
# ---------------------------------------------------------------------------

class SearchCodeInput(BaseModel):
    project_id: str = Field(description="Project the search is scoped to")
    query: str = Field(description="Search query describing what you're looking for")
    limit: Optional[int] = Field(default=None, description="Number of results to return (default 5)")


class ReadFileInput(BaseModel):
    project_id: str = Field(description="Project the file belongs to")
    file_path: str = Field(description="Path to the file")
    start_line: Optional[int] = Field(default=None, description="Starting line (optional)")
    end_line: Optional[int] = Field(default=None, description="Ending line (optional)")


class ListFilesInput(BaseModel):
    project_id: str = Field(description="Project to list files for")


class GrepInput(BaseModel):
    project_id: str = Field(description="Project the search is scoped to")
    pattern: str = Field(description="Python regular expression to search for")
    file_path: Optional[str] = Field(
        default=None,
        description="Optional single file to search; the whole project is searched otherwise",
    )


class FindDependenciesInput(BaseModel):
    project_id: str = Field(description="Project the file belongs to")
    file_path: str = Field(description="Path to the file to analyze")


class AnalyzeSymbolInput(BaseModel):
    project_id: str = Field(description="Project to analyze")
    name: str = Field(description="Function/class/method name to analyze")
    file_path: Optional[str] = Field(
        default=None, description="Optional file path, to disambiguate same-named symbols"
    )


# ---------------------------------------------------------------------------
# Output models (the shape every caller gets back)
# ---------------------------------------------------------------------------

class SearchCodeOutput(BaseModel):
    results: List[Dict[str, Any]] = Field(description="Matching code chunks, best match first")


class ReadFileOutput(BaseModel):
    file_path: str
    content: str
    total_lines: int
    partial: bool = False
    start_line: Optional[int] = None
    end_line: Optional[int] = None
    # Populated when the content hit a context cap; tells the model how to
    # fetch the rest (a line range) instead of silently showing a cut file.
    note: Optional[str] = None


class ListFilesOutput(BaseModel):
    files: List[str] = Field(description="Paths relative to the project root")


class GrepOutput(BaseModel):
    pattern: str
    matches: List[Dict[str, Any]]
    files_scanned: int
    file_path: Optional[str] = None
    notes: Optional[List[str]] = None


class FindDependenciesOutput(BaseModel):
    imports: List[Dict[str, Any]]
    referenced_by: List[str]
    language: str
    notes: Optional[List[str]] = None


class AnalyzeSymbolOutput(BaseModel):
    name: str
    found: bool
    candidates: List[Dict[str, Any]] = []
    similar_names: List[str] = []
    selected_source: Optional[Dict[str, Any]] = None
    callers: List[Dict[str, Any]] = []
    callees: List[Dict[str, Any]] = []
    notes: List[str] = []


# ---------------------------------------------------------------------------
# Handlers (bridge registry inputs to AgentTools)
# ---------------------------------------------------------------------------

def _checked(raw: Any) -> Any:
    """Convert AgentTools' ``{"error": ...}`` results into a raised error."""
    if isinstance(raw, dict) and raw.get("error"):
        raise ToolInvocationError(str(raw["error"]))
    return raw


def _search_code(tools, args: SearchCodeInput) -> SearchCodeOutput:
    results = tools.semantic_search(query=args.query, project_id=args.project_id, limit=args.limit)
    return SearchCodeOutput(results=_checked(results))


def _read_file(tools, args: ReadFileInput) -> ReadFileOutput:
    raw = tools.read_file(
        file_path=args.file_path,
        project_id=args.project_id,
        start_line=args.start_line,
        end_line=args.end_line,
    )
    return ReadFileOutput.model_validate(_checked(raw))


def _list_files(tools, args: ListFilesInput) -> ListFilesOutput:
    return ListFilesOutput(files=list(tools.list_files(args.project_id)))


def _grep(tools, args: GrepInput) -> GrepOutput:
    raw = tools.grep(
        pattern=args.pattern,
        project_id=args.project_id,
        file_path=args.file_path or "",
    )
    return GrepOutput.model_validate(_checked(raw))


def _find_dependencies(tools, args: FindDependenciesInput) -> FindDependenciesOutput:
    raw = tools.find_dependencies(file_path=args.file_path, project_id=args.project_id)
    return FindDependenciesOutput.model_validate(_checked(raw))


def _analyze_symbol(tools, args: AnalyzeSymbolInput) -> AnalyzeSymbolOutput:
    raw = tools.analyze_symbol(
        name=args.name, project_id=args.project_id, file_path=args.file_path
    )
    return AnalyzeSymbolOutput.model_validate(_checked(raw))


# ---------------------------------------------------------------------------
# Registry plumbing
# ---------------------------------------------------------------------------

def _annotation_for(field) -> Any:
    """Rebuild a Pydantic field's annotation for use in a function signature.

    The MCP SDK derives a tool's JSON schema from the function signature, and
    plain type hints drop the ``Field(...)`` metadata (descriptions above all),
    so the metadata is re-attached as ``Annotated`` extras.
    """
    extras: List[Any] = list(field.metadata)
    if field.description is not None:
        extras.insert(0, Field(description=field.description))
    if not extras:
        return field.annotation
    return Annotated[field.annotation, *extras]


def groq_parameters(schema: Dict[str, Any]) -> Dict[str, Any]:
    """Adapt an input model's JSON schema into Groq function parameters.

    Strips ``project_id`` (the agent injects the authoritative value when it
    executes a call, so the model must never choose a project) and the
    Pydantic ``title`` noise.
    """
    parameters = copy.deepcopy(schema)
    parameters.pop("title", None)
    parameters.get("properties", {}).pop(PROJECT_ID_FIELD, None)
    required = [name for name in parameters.get("required", []) if name != PROJECT_ID_FIELD]
    if required:
        parameters["required"] = required
    else:
        parameters.pop("required", None)
    parameters.setdefault("properties", {})
    return parameters


@dataclass(frozen=True)
class ToolSpec:
    """One tool: identity, contract, permission and implementation."""

    name: str
    description: str
    input_model: Type[BaseModel]
    output_model: Type[BaseModel]
    permission: Permission
    handler: Callable[[Any, BaseModel], BaseModel]

    def invoke(self, tools, arguments: Dict[str, Any]) -> BaseModel:
        """Validate the arguments, run the handler and validate the result."""
        args = self.input_model.model_validate(arguments)
        result = self.handler(tools, args)
        if isinstance(result, self.output_model):
            return result
        return self.output_model.model_validate(result)

    def groq_function(self) -> Dict[str, Any]:
        """The Groq function-calling definition, generated from the model."""
        return groq_function_from_mcp(
            self.name, self.description, self.input_model.model_json_schema()
        )


def groq_function_from_mcp(
    name: str, description: str, input_schema: Dict[str, Any]
) -> Dict[str, Any]:
    """Build a Groq function definition from a discovered MCP input schema.

    The agent uses this on the schemas the MCP server advertises, so both the
    local registry and the live server produce identical Groq definitions.
    """
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description or "",
            "parameters": groq_parameters(input_schema),
        },
    }


class ToolRegistry:
    """Ordered collection of tool specs with lookup and filtering."""

    def __init__(self, specs: List[ToolSpec]):
        self._specs: Dict[str, ToolSpec] = {}
        for spec in specs:
            if spec.name in self._specs:
                raise ValueError(f"Duplicate tool in registry: {spec.name}")
            self._specs[spec.name] = spec

    def get(self, name: str) -> ToolSpec:
        try:
            return self._specs[name]
        except KeyError:
            raise KeyError(f"Unknown tool: {name}") from None

    def list(self) -> List[ToolSpec]:
        return list(self._specs.values())

    def names(self) -> List[str]:
        return list(self._specs)

    def read_only(self) -> List[ToolSpec]:
        return [spec for spec in self._specs.values() if spec.permission == "read"]

    def groq_definitions(self) -> List[Dict[str, Any]]:
        return [spec.groq_function() for spec in self.list()]

REGISTRY = ToolRegistry(
    [
        ToolSpec(
            name="search_code",
            description=(
                "Search the codebase by semantic meaning. Use this to find relevant code."
            ),
            input_model=SearchCodeInput,
            output_model=SearchCodeOutput,
            permission="read",
            handler=_search_code,
        ),
        ToolSpec(
            name="read_file",
            description="Read a file's content. Optionally specify line range for large files.",
            input_model=ReadFileInput,
            output_model=ReadFileOutput,
            permission="read",
            handler=_read_file,
        ),
        ToolSpec(
            name="list_files",
            description="List all files in the project repository",
            input_model=ListFilesInput,
            output_model=ListFilesOutput,
            permission="read",
            handler=_list_files,
        ),
        ToolSpec(
            name="grep",
            description=(
                "Search file contents for an exact string or regular expression. "
                "Use it for literal identifiers; use search_code for concepts."
            ),
            input_model=GrepInput,
            output_model=GrepOutput,
            permission="read",
            handler=_grep,
        ),
        ToolSpec(
            name="find_dependencies",
            description="Find imports and dependencies for a specific file",
            input_model=FindDependenciesInput,
            output_model=FindDependenciesOutput,
            permission="read",
            handler=_find_dependencies,
        ),
        ToolSpec(
            name="analyze_symbol",
            description=(
                "Deep-dive one declared symbol: its source, who calls it, and what it calls. "
                "Use it to trace dependencies accurately instead of guessing."
            ),
            input_model=AnalyzeSymbolInput,
            output_model=AnalyzeSymbolOutput,
            permission="read",
            handler=_analyze_symbol,
        ),
    ]
)



