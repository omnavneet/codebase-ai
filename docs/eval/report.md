# Codebase-AI Evaluation Report

**Generated:** 2026-11-15 10:30:03
**Dataset:** eval/questions.json v1.0.0 (36 questions)
- `codebase-ai` pinned at `4b32ac70c13c1d9a8819844acd603c62f82e6ea8`
- `httprouter` pinned at `484018016424d215c0b87c42f4c9b57d980fbd00`

## Summary

| Metric | Value |
|---|---|
| precision@5 | 0.1556 |
| precision@10 | 0.0889 |
| recall@5 | 0.6644 |
| recall@10 | 0.7153 |
| MRR | 0.5810 |
| hit-rate@5 | 0.7778 |
| hit-rate@10 | 0.8333 |
| citation accuracy | n/a |
| answer accuracy (per reference point) | n/a |
| answers passing all reference points | n/a |
| avg latency (s) | n/a |
| p95 latency (s) | n/a |
| avg tool calls | n/a |
| avg iterations | n/a |
| avg cost / question (USD) | n/a |
| total cost (USD) | n/a |

## Retrieval: per-question results

| ID | Repo | Type | P@5 | P@10 | R@5 | R@10 | MRR | Hit@5 | Error |
|---|---|---|---|---|---|---|---|---|---|
| q001 | codebase-ai | locate | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q002 | codebase-ai | locate | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q003 | codebase-ai | locate | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |  |
| q004 | codebase-ai | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q005 | codebase-ai | locate | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q006 | codebase-ai | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q007 | codebase-ai | explain | 0.200 | 0.100 | 1.000 | 1.000 | 0.250 | 1.000 |  |
| q008 | codebase-ai | explain | 0.200 | 0.100 | 1.000 | 1.000 | 0.250 | 1.000 |  |
| q009 | codebase-ai | explain | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q010 | codebase-ai | explain | 0.000 | 0.100 | 0.000 | 1.000 | 0.167 | 0.000 |  |
| q011 | codebase-ai | explain | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |  |
| q012 | codebase-ai | explain | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q013 | codebase-ai | trace-call-flow | 0.000 | 0.100 | 0.000 | 0.250 | 0.167 | 0.000 |  |
| q014 | codebase-ai | trace-call-flow | 0.200 | 0.100 | 0.333 | 0.333 | 1.000 | 1.000 |  |
| q015 | codebase-ai | trace-call-flow | 0.200 | 0.200 | 0.250 | 0.500 | 0.500 | 1.000 |  |
| q016 | codebase-ai | trace-call-flow | 0.200 | 0.200 | 0.333 | 0.667 | 1.000 | 1.000 |  |
| q017 | codebase-ai | trace-call-flow | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |  |
| q018 | codebase-ai | trace-call-flow | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |  |
| q019 | httprouter | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q020 | httprouter | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q021 | httprouter | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.250 | 1.000 |  |
| q022 | httprouter | locate | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |  |
| q023 | httprouter | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q024 | httprouter | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.333 | 1.000 |  |
| q025 | httprouter | explain | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q026 | httprouter | explain | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q027 | httprouter | explain | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q028 | httprouter | explain | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q029 | httprouter | explain | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |  |
| q030 | httprouter | explain | 0.200 | 0.100 | 0.500 | 0.500 | 1.000 | 1.000 |  |
| q031 | httprouter | trace-call-flow | 0.200 | 0.100 | 0.500 | 0.500 | 1.000 | 1.000 |  |
| q032 | httprouter | trace-call-flow | 0.200 | 0.100 | 0.500 | 0.500 | 1.000 | 1.000 |  |
| q033 | httprouter | trace-call-flow | 0.200 | 0.100 | 0.500 | 0.500 | 1.000 | 1.000 |  |
| q034 | httprouter | trace-call-flow | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |
| q035 | httprouter | locate | 0.200 | 0.100 | 1.000 | 1.000 | 0.500 | 1.000 |  |
| q036 | httprouter | locate | 0.200 | 0.100 | 1.000 | 1.000 | 1.000 | 1.000 |  |

### Retrieval failures

- **q003** (codebase-ai, locate): Where is the pgvector similarity query that backs semantic search?
  - expected: ['backend/src/main/java/com/codebaseai/backend/repository/CodeChunkRepository.java']
  - retrieved: ['ai-service/agent_tools.py', 'backend/src/main/java/com/codebaseai/backend/service/ProjectService.java', 'README.md', 'ai-service/test_mcp_server.py', 'frontend/src/components/SearchPanel.tsx', 'ai-service/tool_registry.py', 'backend/pom.xml']
- **q010** (codebase-ai, explain): How does the AI service authenticate server-to-server requests?
  - expected: ['ai-service/main.py']
  - retrieved: ['README.md', 'backend/src/main/java/com/codebaseai/backend/config/AppProperties.java', 'ai-service/mcp_server.py', 'backend/src/main/java/com/codebaseai/backend/service/AiServiceClient.java', 'docker-compose.yml', 'ai-service/main.py']
- **q011** (codebase-ai, explain): How does the storage layer block path traversal when reading project files?
  - expected: ['ai-service/storage.py']
  - retrieved: ['backend/src/main/java/com/codebaseai/backend/storage/StorageService.java', 'backend/src/main/java/com/codebaseai/backend/storage/StoragePaths.java', 'ai-service/test_storage.py', 'ai-service/test_mcp_server.py', 'backend/src/main/java/com/codebaseai/backend/storage/LocalStorageService.java', 'ai-service/agent_tools.py', 'backend/src/main/java/com/codebaseai/backend/service/ProjectService.java', 'backend/src/main/java/com/codebaseai/backend/service/CodeProcessingService.java']
- **q013** (codebase-ai, trace-call-flow): Trace the full path of a semantic search request from the HTTP endpoint down to pgvector and back.
  - expected: ['backend/src/main/java/com/codebaseai/backend/controller/ProjectController.java', 'backend/src/main/java/com/codebaseai/backend/service/ProjectService.java', 'backend/src/main/java/com/codebaseai/backend/service/AiServiceClient.java', 'backend/src/main/java/com/codebaseai/backend/repository/CodeChunkRepository.java']
  - retrieved: ['ai-service/agent_tools.py', 'README.md', 'ai-service/test_mcp_server.py', 'ai-service/debug_service.py', 'frontend/src/components/SearchPanel.tsx', 'backend/src/main/java/com/codebaseai/backend/service/ProjectService.java']
- **q014** (codebase-ai, trace-call-flow): Trace how a single agent tool call flows from the LLM to the code and back.
  - expected: ['ai-service/agent.py', 'ai-service/mcp_client.py', 'ai-service/mcp_server.py']
  - retrieved: ['ai-service/agent.py', 'ai-service/main.py', 'ai-service/debug_service.py', 'backend/src/main/java/com/codebaseai/backend/service/AiServiceClient.java', 'ai-service/test_agent.py', 'README.md', 'ai-service/tool_registry.py', 'ai-service/test_mcp_server.py']
- **q015** (codebase-ai, trace-call-flow): Trace what happens to an uploaded project ZIP from the HTTP request to a searchable index.
  - expected: ['backend/src/main/java/com/codebaseai/backend/controller/ProjectController.java', 'backend/src/main/java/com/codebaseai/backend/service/ProjectService.java', 'backend/src/main/java/com/codebaseai/backend/service/ZipExtractionService.java', 'backend/src/main/java/com/codebaseai/backend/service/CodeProcessingService.java']
  - retrieved: ['backend/src/main/java/com/codebaseai/backend/service/ZipLimitExceededException.java', 'backend/src/main/java/com/codebaseai/backend/service/ProjectService.java', 'backend/src/main/java/com/codebaseai/backend/storage/StorageService.java', 'backend/src/test/java/com/codebaseai/backend/storage/LocalStorageServiceTest.java', 'backend/src/main/java/com/codebaseai/backend/storage/LocalStorageService.java', 'backend/src/main/java/com/codebaseai/backend/service/CodeProcessingService.java']
- **q016** (codebase-ai, trace-call-flow): Trace the account registration and email verification flow end to end.
  - expected: ['backend/src/main/java/com/codebaseai/backend/controller/AuthController.java', 'backend/src/main/java/com/codebaseai/backend/service/AuthService.java', 'backend/src/main/java/com/codebaseai/backend/service/EmailVerificationService.java']
  - retrieved: ['backend/src/main/java/com/codebaseai/backend/service/AuthService.java', 'README.md', 'frontend/src/pages/AuthPage.tsx', 'backend/src/main/java/com/codebaseai/backend/service/MailService.java', 'frontend/src/context/AuthContext.tsx', 'backend/src/main/resources/db/migration/V11__add_email_verification.sql', 'backend/src/main/java/com/codebaseai/backend/controller/AuthController.java']
- **q017** (codebase-ai, trace-call-flow): Trace the checks a request must pass before the investigation agent runs for a project.
  - expected: ['backend/src/main/java/com/codebaseai/backend/config/SecurityConfig.java', 'backend/src/main/java/com/codebaseai/backend/config/VerifiedUserFilter.java', 'backend/src/main/java/com/codebaseai/backend/config/InMemoryRateLimitFilter.java']
  - retrieved: ['README.md', 'docker-compose.prod.yml', 'backend/src/main/java/com/codebaseai/backend/service/AgentService.java', 'ai-service/main.py', 'ai-service/debug_service.py', 'backend/src/main/java/com/codebaseai/backend/service/AiServiceClient.java', 'ai-service/test_agent.py']
- **q018** (codebase-ai, trace-call-flow): Trace what happens inside the AI service when a POST /chat request arrives.
  - expected: ['ai-service/main.py', 'ai-service/chat_service.py']
  - retrieved: ['backend/src/main/java/com/codebaseai/backend/service/AiServiceClient.java', 'backend/src/main/java/com/codebaseai/backend/config/AppProperties.java', 'README.md', 'backend/src/main/java/com/codebaseai/backend/service/ChatService.java', 'backend/src/main/java/com/codebaseai/backend/controller/ChatController.java']
- **q022** (httprouter, locate): Where is the route lookup function that returns the handler, params and trailing-slash flag?
  - expected: ['tree.go']
  - retrieved: ['router.go', 'README.md']
- **q029** (httprouter, explain): How are named parameters and catch-all routes represented in the routing tree?
  - expected: ['tree.go']
  - retrieved: ['README.md', 'tree_test.go', 'router.go']
- **q030** (httprouter, explain): How does RedirectFixedPath rescue messy request paths such as //foo//bar or wrong casing?
  - expected: ['router.go', 'tree.go']
  - retrieved: ['router.go', 'README.md', 'path.go']
- **q031** (httprouter, trace-call-flow): Trace an incoming HTTP request from ServeHTTP to the final handler function.
  - expected: ['router.go', 'tree.go']
  - retrieved: ['router.go', 'README.md', 'router_test.go']
- **q032** (httprouter, trace-call-flow): Trace how Router.GET('/path', handler) becomes a routable entry.
  - expected: ['router.go', 'tree.go']
  - retrieved: ['router.go', 'README.md', 'router_test.go']
- **q033** (httprouter, trace-call-flow): Trace what happens when a request misses a registered route only by a trailing slash.
  - expected: ['router.go', 'tree.go']
  - retrieved: ['router.go', 'README.md', 'router_test.go']

## Agent suite

Not run yet - execute `python -m eval.run --suite agent` (needs a Groq key).
