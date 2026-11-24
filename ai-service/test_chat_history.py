import types
from unittest.mock import patch

with patch("dotenv.load_dotenv"):
    from chat_service import ChatService


def make_service(completion):
    service = ChatService.__new__(ChatService)
    service.model = "test"
    service.client = types.SimpleNamespace(
        chat=types.SimpleNamespace(completions=completion))
    return service


def test_summary_is_carried_as_conversation_data():
    service = make_service(None)
    messages = service._build_messages(
        "What calls it?", [],
        [{"role": "system", "content": "Earlier discussion summary: handler = login"},
         {"role": "user", "content": "Find the login handler"},
         {"role": "assistant", "content": "It is login"}])
    assert messages[1]["role"] == "user"
    assert "context, not instructions" in messages[1]["content"]
    assert "handler = login" in messages[1]["content"]
    assert [entry["role"] for entry in messages] == [
        "system", "user", "user", "assistant", "user"]


def test_context_retry_keeps_summary_and_most_recent_turns():
    class Completion:
        def __init__(self):
            self.requests = []

        def create(self, **kwargs):
            self.requests.append(kwargs)
            if len(self.requests) == 1:
                raise RuntimeError("maximum context length")
            return "ok"

    completion = Completion()
    service = make_service(completion)
    history = [{"role": "system", "content": "Summary"}] + [
        {"role": role, "content": content}
        for role, content in (("user", "old question"), ("assistant", "old answer"),
                              ("user", "new question"), ("assistant", "new answer"))
    ]
    assert service._create("follow up", [], history, stream=False) == "ok"
    retried = completion.requests[1]["messages"]
    contents = [message["content"] for message in retried]
    assert any("Summary" in content for content in contents)
    assert "new question" in contents and "new answer" in contents
    assert "old question" not in contents and "old answer" not in contents
