package com.codebaseai.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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
}