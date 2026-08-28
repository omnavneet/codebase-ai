package com.codebaseai.backend.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.dto.ChatMessageResponse;
import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.ChatSession;
import com.codebaseai.backend.model.CodeChunk;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ChatSessionRepository;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    
    @Transactional
    public ChatSession createSession(UUID projectId, UUID userId) {
        ChatSession session = new ChatSession();
        session.setProjectId(projectId);
        session.setUserId(userId);
        session.setTitle("New Chat");
        return chatSessionRepository.save(session);
    }
    
    public List<ChatSession> getSessions(UUID projectId) {
        return chatSessionRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }
    
    public List<ChatMessage> getMessages(UUID sessionId) {
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }
    
    @Transactional
    public ChatMessageResponse sendMessage(UUID sessionId, String question) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        
        // Save user message
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(question);
        chatMessageRepository.save(userMessage);
        
        // Generate embedding for question
        List<List<Double>> questionEmbeddings = aiServiceClient.generateEmbeddings(
            List.of(question)
        );
        List<Double> questionEmbedding = questionEmbeddings.get(0);
        
        // Search similar chunks
        String embeddingString = Arrays.toString(questionEmbedding.toArray());
        List<CodeChunk> similarChunks = codeChunkRepository.findSimilarChunks(
            session.getProjectId(),
            embeddingString,
            5  // Top 5 chunks
        );
        
        // Build context
        List<Map<String, Object>> context = similarChunks.stream()
                .map(chunk -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("content", chunk.getContent());
                    ctx.put("start_line", chunk.getStartLine());
                    ctx.put("end_line", chunk.getEndLine());
                    // Need to get file path - you'll need to fetch it
                    String filePath = projectFileRepository.findById(chunk.getFileId())
                    .map(ProjectFile::getPath)
                    .orElse("unknown");
                    ctx.put("file_path", filePath);

                    return ctx;
                })
                .collect(Collectors.toList());
        
        // Generate answer
        Map<String, Object> aiResponse = aiServiceClient.chat(question, context);
        String answer = (String) aiResponse.get("answer");
        
        // Save assistant message
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(sessionId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(answer);
        
        try {
            assistantMessage.setCitations(objectMapper.writeValueAsString(context));
        } catch (Exception e) {
            log.error("Failed to serialize citations", e);
        }
        
        chatMessageRepository.save(assistantMessage);
        
        // Update session title if first message
        if (session.getTitle().equals("New Chat")) {
            session.setTitle(question.substring(0, Math.min(50, question.length())));
            chatSessionRepository.save(session);
        }
        
        return new ChatMessageResponse(answer, context);
    }
}