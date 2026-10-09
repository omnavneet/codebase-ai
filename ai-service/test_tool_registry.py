"""Contract tests for the tool registry: schemas, permissions and lookup.

The registry is the single source of truth for the Groq schemas the model
sees, so these tests pin the generated JSON to the Pydantic input models and
verify that only read-level tools are registered.
"""

import pytest

from tool_registry import PROJECT_ID_FIELD, REGISTRY, groq_function_from_mcp

EXPECTED_TOOLS = [
    "search_code",
    "read_file",
    "list_files",
    "grep",
    "find_dependencies",
    "analyze_symbol",
]


def test_registry_exposes_exactly_the_expected_tools():
    assert REGISTRY.names() == EXPECTED_TOOLS


def test_every_registered_tool_is_read_level():
    assert all(spec.permission == "read" for spec in REGISTRY.list())
    assert REGISTRY.read_only() == REGISTRY.list()


def test_unknown_tool_lookup_raises():
    with pytest.raises(KeyError, match="Unknown tool"):
        REGISTRY.get("delete_everything")


@pytest.mark.parametrize("spec", REGISTRY.list(), ids=REGISTRY.names())
def test_groq_schema_is_generated_from_the_input_model(spec):
    """No hand-written JSON: the definition is the input model, projected."""
    definition = spec.groq_function()
    assert definition["type"] == "function"
    function = definition["function"]
    assert function["name"] == spec.name
    assert function["description"] == spec.description

    parameters = function["parameters"]
    model_schema = spec.input_model.model_json_schema()
    assert parameters["type"] == "object"

    # project_id is injected by the agent at call time; the model must never
    # see it as an argument it could choose.
    assert PROJECT_ID_FIELD not in parameters["properties"]
    assert PROJECT_ID_FIELD not in parameters.get("required", [])

    expected_properties = set(model_schema["properties"]) - {PROJECT_ID_FIELD}
    assert set(parameters["properties"]) == expected_properties
    assert set(parameters.get("required", [])) == (
        set(model_schema.get("required", [])) - {PROJECT_ID_FIELD}
    )

    # Field descriptions survive into the schema the model reads.
    for name in expected_properties:
        assert parameters["properties"][name].get("description") == (
            model_schema["properties"][name].get("description")
        )


@pytest.mark.parametrize("spec", REGISTRY.list(), ids=REGISTRY.names())
def test_groq_builder_does_not_mutate_the_discovered_schema(spec):
    """The agent feeds MCP-discovered schemas through the same builder, so it
    must not rewrite the schema it was given (it is reused for tool calls)."""
    discovered = spec.input_model.model_json_schema()
    snapshot = {key: repr(value) for key, value in discovered.items()}
    rebuilt = groq_function_from_mcp(spec.name, spec.description, discovered)
    assert rebuilt == spec.groq_function()
    assert {key: repr(value) for key, value in discovered.items()} == snapshot
