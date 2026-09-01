package com.codebaseai.backend.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.dto.ChatMessageResponse;
import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.ChatSession;
import com.codebaseai.backend.model.CodeChunk;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ChatSessionRepository;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    private final ProjectRepository projectRepository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    
    @Transactional
    public ChatSession createSession(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        ChatSession session = new ChatSession();
        session.setProjectId(projectId);
        session.setUserId(userId);
        session.setTitle("New Chat");
        return chatSessionRepository.save(session);
    }

    public List<ChatSession> getSessions(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return chatSessionRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    public List<ChatMessage> getMessages(UUID sessionId, UUID userId) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public void deleteSession(UUID sessionId, UUID userId) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        chatMessageRepository.deleteAll(
                chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
        chatSessionRepository.deleteById(sessionId);
    }

    @Transactional
    public ChatSession renameSession(UUID sessionId, UUID userId, String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session title cannot be empty");
        }

        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        session.setTitle(title.trim());
        return chatSessionRepository.save(session);
    }

    @Transactional
    public ChatMessageResponse sendMessage(UUID sessionId, UUID userId, String question) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (question == null || question.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question cannot be empty");
        }

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(question.trim());
        chatMessageRepository.save(userMessage);

        List<List<Double>> questionEmbeddings = aiServiceClient.generateEmbeddings(
            List.of(question.trim())
        );
        List<Double> questionEmbedding = questionEmbeddings.get(0);

        String embeddingString = Arrays.toString(questionEmbedding.toArray());
        List<CodeChunk> similarChunks = codeChunkRepository.findSimilarChunks(
            session.getProjectId(),
            embeddingString,
            5
        );

        List<Map<String, Object>> context = similarChunks.stream()
                .map(chunk -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("content", chunk.getContent());
                    ctx.put("start_line", chunk.getStartLine());
                    ctx.put("end_line", chunk.getEndLine());
                    String filePath = projectFileRepository.findById(chunk.getFileId())
                            .map(ProjectFile::getPath)
                            .orElse("unknown");
                    ctx.put("file_path", filePath);
                    return ctx;
                })
                .collect(Collectors.toList());

        Map<String, Object> aiResponse = aiServiceClient.chat(question.trim(), context);
        String answer = (String) aiResponse.get("answer");

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(sessionId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(answer);

        try {
            assistantMessage.setCitations(objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize citations", e);
        }

        chatMessageRepository.save(assistantMessage);

        if (session.getTitle().equals("New Chat")) {
            session.setTitle(question.trim().substring(0, Math.min(50, question.trim().length())));
            chatSessionRepository.save(session);
        }

        return new ChatMessageResponse(answer, context);
    }
}