package com.codebaseai.backend.repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.codebaseai.backend.model.Project;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") UUID id);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "INSERT INTO published_generations(project_id, generation_id) VALUES (:projectId, :generationId)", nativeQuery = true)
    void recordPublishedGeneration(@Param("projectId") UUID projectId, @Param("generationId") UUID generationId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM published_generations WHERE project_id = :projectId AND generation_id = :generationId)", nativeQuery = true)
    boolean isGenerationPublished(@Param("projectId") UUID projectId, @Param("generationId") UUID generationId);
}

