package com.codebaseai.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentInvestigateResponse {
    private String answer;
    private List<String> trace;
    private int iterations;
    private List<String> filesRead;
    private List<String> searchesPerformed;
    private boolean truncated;
}
