package com.codebaseai.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.model.ProjectFile;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, UUID> {
    List<ProjectFile> findByProjectId(UUID projectId);

    List<ProjectFile> findByProjectIdAndGenerationId(UUID projectId, UUID generationId);

    @Transactional
    @Modifying
    @Query("delete from ProjectFile f where f.projectId = :projectId and f.generationId = :generationId")
    void deleteByProjectIdAndGenerationId(@Param("projectId") UUID projectId,
                                          @Param("generationId") UUID generationId);

    @Transactional
    @Modifying
    @Query("delete from ProjectFile f where f.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);
}
