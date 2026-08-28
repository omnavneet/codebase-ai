package com.codebaseai.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.codebaseai.backend.model.ChatSession;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByProjectIdOrderByUpdatedAtDesc(UUID projectId);
}