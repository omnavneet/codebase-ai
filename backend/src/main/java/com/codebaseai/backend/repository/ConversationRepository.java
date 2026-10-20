package com.codebaseai.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.codebaseai.backend.model.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findByProjectIdOrderByUpdatedAtDesc(UUID projectId);
    Optional<Conversation> findByIdAndProjectId(UUID id, UUID projectId);
}