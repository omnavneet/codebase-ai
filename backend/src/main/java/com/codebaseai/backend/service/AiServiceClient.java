package com.codebaseai.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                .block();
        
        return (List<List<Double>>) response.get("embeddings");
    }
    
    public Map<String, Object> chat(String question, List<Map<String, Object>> context) {
        Map<String, Object> request = Map.of(
            "question", question,
            "context", context
        );
        
        return webClient.post()
                .uri(aiServiceUrl + "/chat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    /**
     * Run a bounded agent investigation for a project. The agent can take
     * multiple LLM round-trips, so this uses a generous timeout.
     */
    public Map<String, Object> investigate(String question, String projectId) {
        Map<String, Object> request = Map.of(
            "question", question,
            "project_id", projectId
        );

        return webClient.post()
                .uri(aiServiceUrl + "/agent/investigate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofMinutes(5));
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

        return webClient.post()
                .uri(aiServiceUrl + "/agent/generate-docs")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofMinutes(5));
    }

    /**
     * Investigate the project with the agent and generate a README from the findings.
     */
    public Map<String, Object> generateReadme(String projectId) {
        return webClient.post()
                .uri(aiServiceUrl + "/agent/generate-readme")
                .bodyValue(Map.of("project_id", projectId))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofMinutes(5));
    }
}