package com.codebaseai.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentInvestigateResponse {
    private String answer;
    private List<String> trace;
    private int iterations;
    @JsonProperty("tool_calls")
    private int toolCalls;
    private List<String> filesRead;
    private List<String> searchesPerformed;
    private boolean truncated;
    private Usage usage;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Usage {
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
