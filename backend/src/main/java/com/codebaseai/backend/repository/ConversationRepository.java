package com.codebaseai.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.codebaseai.backend.model.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findByProjectIdOrderByUpdatedAtDesc(UUID projectId);
    Optional<Conversation> findByIdAndProjectId(UUID id, UUID projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id and c.projectId = :projectId")
    Optional<Conversation> lockByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);
}
