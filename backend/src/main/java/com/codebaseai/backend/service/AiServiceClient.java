package com.codebaseai.backend.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.config.AppProperties;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AiServiceClient {

    private final AppProperties properties;

    private final WebClient webClient;

    // Explicit constructor (not @RequiredArgsConstructor): the WebClient needs a
    // configured ExchangeStrategies, and Lombok would otherwise also generate a
    // second (AppProperties, WebClient) constructor that leaves Spring unable to
    // pick one ("No default constructor found" at startup).
    public AiServiceClient(AppProperties properties) {
        this.properties = properties;
        // The framework's default in-memory buffer cap (256 KB) is far too small
        // for an embeddings batch, so raise it via ExchangeStrategies. Without
        // this, generateEmbeddings fails with DataBufferLimitException on any
        // non-trivial upload.
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(properties.getAiService().getMaxInMemoryBytes()))
                .build();
        this.webClient = WebClient.builder()
                .exchangeStrategies(strategies)
                .build();
    }

    private String baseUrl() {
        return properties.getAiService().getUrl();
    }

    private String internalToken() {
        return properties.getAiService().getInternalToken();
    }

    /**
     * Block on a single-shot AI call with a bounded timeout. Transport failures,
     * HTTP error statuses and timeouts all surface as 502 with a clear reason,
     * so a hung AI service can never pin a Tomcat thread forever.
     */
    private <T> T block(Mono<T> mono, Duration timeout, String call) {
        try {
            return mono.block(timeout);
        } catch (WebClientException | IllegalStateException e) {
            log.error("AI service call '{}' failed", call, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service call '" + call + "' failed or timed out", e);
        }
    }

    public List<List<Double>> generateEmbeddings(List<String> texts) {
        Map<String, Object> request = Map.of("texts", texts);

        Map<String, Object> response = block(
                webClient.post()
                        .uri(baseUrl() + "/embed")
                    .header("X-Internal-Token", internalToken())
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(Map.class),
                Duration.ofSeconds(properties.getAiService().getTimeoutSeconds()),
                "embed");

        return (List<List<Double>>) response.get("embeddings");
    }
    
    public Map<String, Object> chat(
            String question, List<Map<String, Object>> context, List<Map<String, String>> history) {
        Map<String, Object> request = Map.of(
            "question", question,
            "context", context,
            "history", history
        );

        return block(
                webClient.post()
                        .uri(baseUrl() + "/chat")
                    .header("X-Internal-Token", internalToken())
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(Map.class),
                Duration.ofSeconds(properties.getAiService().getTimeoutSeconds()),
                "chat");
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
                .uri(baseUrl() + "/chat/stream")
            .header("X-Internal-Token", internalToken())
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    /**
     * Relay one of the AI service's agent {@code /stream} endpoints as an SSE
     * flux of {@code step}/{@code status} events followed by a terminal
     * {@code done}/{@code error}. Reuses the same WebFlux path as the chat
     * stream, so the agent endpoints now stream progress instead of blocking
     * on a single long POST.
     *
     * @param path the AI-service path, e.g. {@code /agent/investigate/stream}
     */
    public Flux<ServerSentEvent<String>> streamAgent(String path, Map<String, Object> request) {
        return webClient.post()
                .uri(baseUrl() + path)
                .header("X-Internal-Token", internalToken())
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
        return block(
                webClient.post()
                        .uri(baseUrl() + path)
                    .header("X-Internal-Token", internalToken())
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(Map.class),
                Duration.ofSeconds(properties.getAiService().getAgentTimeoutSeconds()),
                path);
    }
}