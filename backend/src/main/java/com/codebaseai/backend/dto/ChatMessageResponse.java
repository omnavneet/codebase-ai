package com.codebaseai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class ChatMessageResponse {
    private String answer;
    private List<Map<String, Object>> citations;
}