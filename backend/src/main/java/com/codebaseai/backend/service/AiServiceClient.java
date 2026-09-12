package com.codebaseai.backend.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiServiceClient {
    
    @Value("${app.ai-service.url}")
    private String aiServiceUrl;
    
    private final WebClient webClient = WebClient.create();
    
    public List<List<Double>> generateEmbeddings(List<String> texts) {
        Map<String, Object> request = Map.of("texts", texts);
        
        Map<String, Object> response = webClient.post()
                .uri(aiServiceUrl + "/embed")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                // Bounded block: a hung AI service must not pin a Tomcat
                // thread forever (the default block() waits indefinitely).
                .block(Duration.ofSeconds(60));
        
        return (List<List<Double>>) response.get("embeddings");
    }
    
    public Map<String, Object> chat(
            String question, List<Map<String, Object>> context, List<Map<String, String>> history) {
        Map<String, Object> request = Map.of(
            "question", question,
            "context", context,
            "history", history
        );
        
        return webClient.post()
                .uri(aiServiceUrl + "/chat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                // Bounded block: see generateEmbeddings. Generous, because the
                // LLM call itself usually completes well inside this window.
                .block(Duration.ofSeconds(60));
    }

    /**
     * Stream a RAG answer from the AI service as Server-Sent Events.
     * Events carry JSON payloads with the event names "token", "done" or
     * "error" (emitted by the Python service).
     */
    public Flux<ServerSentEvent<String>> chatStream(
            String question, List<Map<String, Object>> context, List<Map<String, String>> history) {
        Map<String, Object> request = Map.of(
                "question", question,
                "context", context,
                "history", history);

        return webClient.post()
                .uri(aiServiceUrl + "/chat/stream")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    /**
     * Run a bounded agent investigation for a project. The agent can take
     * multiple LLM round-trips, so this uses a generous timeout.
     */
    public Map<String, Object> investigate(String question, String projectId) {
        return callAgent("/agent/investigate", Map.of(
                "question", question,
                "project_id", projectId));
    }

    /**
     * Generate doc comments for a file (or a specific symbol in it).
     */
    public Map<String, Object> generateDocs(String filePath, String projectId, String symbol) {
        Map<String, Object> request = new HashMap<>();
        request.put("file_path", filePath);
        request.put("project_id", projectId);
        if (symbol != null && !symbol.isBlank()) {
            request.put("symbol", symbol);
        }
        return callAgent("/agent/generate-docs", request);
    }

    /**
     * Investigate the project with the agent and generate a README from the findings.
     */
    public Map<String, Object> generateReadme(String projectId) {
        return callAgent("/agent/generate-readme", Map.of("project_id", projectId));
    }

    /** Explain a file (or symbol) in depth using the investigation agent. */
    public Map<String, Object> explainCode(String filePath, String projectId, String symbol) {
        Map<String, Object> request = new HashMap<>();
        request.put("file_path", filePath);
        request.put("project_id", projectId);
        if (symbol != null && !symbol.isBlank()) {
            request.put("symbol", symbol);
        }
        return callAgent("/agent/explain-code", request);
    }

    /** Investigate a reported issue and find the root cause and fix. */
    public Map<String, Object> debug(String issueDescription, String projectId, String stackTrace, String filePath) {
        Map<String, Object> request = new HashMap<>();
        request.put("issue_description", issueDescription);
        request.put("project_id", projectId);
        if (stackTrace != null && !stackTrace.isBlank()) {
            request.put("stack_trace", stackTrace);
        }
        if (filePath != null && !filePath.isBlank()) {
            request.put("file_path", filePath);
        }
        return callAgent("/agent/debug", request);
    }

    /** Review a file for bugs, performance, security and readability issues. */
    public Map<String, Object> improveCode(String filePath, String projectId) {
        return callAgent("/agent/improve-code", Map.of(
                "file_path", filePath,
                "project_id", projectId));
    }

    /**
     * Call an agent endpoint. Agent runs take multiple LLM round-trips,
     * so this uses a generous timeout.
     */
    private Map<String, Object> callAgent(String path, Map<String, Object> request) {
        return webClient.post()
                .uri(aiServiceUrl + path)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofMinutes(5));
    }
}