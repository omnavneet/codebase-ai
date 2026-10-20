package com.codebaseai.backend.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.Conversation;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ConversationRepository;
import com.codebaseai.backend.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

/**
 * Project-scoped conversations. Ownership is checked through the project
 * (same rule as chat), so a conversation must belong to the project in the
 * path — otherwise 404. Message deletes cascade from the conversation row.
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;

    /** Newest activity first, for the sidebar list (id, title, updatedAt). */
    public List<Conversation> listConversations(UUID projectId, UUID userId) {
        requireOwnedProject(projectId, userId);
        return conversationRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    /** New, empty conversation. Nothing is shown until the first message lands. */
    @Transactional
    public Conversation createConversation(UUID projectId, UUID userId, String title) {
        requireOwnedProject(projectId, userId);
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        String trimmed = title == null ? "" : title.trim();
        conversation.setTitle(trimmed.isEmpty() ? "New Chat" : trimmed);
        return conversationRepository.save(conversation);
    }

    /** Rename; titles are trimmed and bounded to 1-80 chars. */
    @Transactional
    public Conversation renameConversation(UUID projectId, UUID userId, UUID conversationId, String title) {
        requireOwnedProject(projectId, userId);
        Conversation conversation = ownedConversation(projectId, conversationId);
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title must be 1-80 characters");
        }
        conversation.setTitle(trimmed);
        return conversationRepository.save(conversation);
    }

    /** Delete; messages cascade from the conversation's FK, then the row goes. */
    @Transactional
    public void deleteConversation(UUID projectId, UUID userId, UUID conversationId) {
        requireOwnedProject(projectId, userId);
        ownedConversation(projectId, conversationId);
        conversationRepository.deleteById(conversationId);
    }

    /** The conversation's messages, oldest first, in the existing DTO shape. */
    public List<ChatMessage> getMessages(UUID projectId, UUID userId, UUID conversationId) {
        requireOwnedProject(projectId, userId);
        return chatMessageRepository
                .findByConversationIdOrderByCreatedAtAsc(ownedConversation(projectId, conversationId).getId());
    }

    /**
     * Resolve the conversation an incoming message belongs to: the given id
     * when it exists (and belongs to the project), otherwise the project's
     * most recent conversation, creating an empty one if none exists — so old
     * clients that never send an id keep working.
     */
    @Transactional
    public Conversation resolveOrCreate(
            UUID projectId, UUID userId, UUID conversationId) {
        Project project = requireOwnedProject(projectId, userId);
        if (conversationId != null) {
            return ownedConversation(projectId, conversationId);
        }
        return conversationRepository.findByProjectIdOrderByUpdatedAtDesc(project.getId()).stream()
                .findFirst()
                .orElseGet(() -> createConversation(project.getId(), userId, "New Chat"));
    }

    private Conversation ownedConversation(UUID projectId, UUID conversationId) {
        return conversationRepository.findByIdAndProjectId(conversationId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    private Project requireOwnedProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return project;
    }
}