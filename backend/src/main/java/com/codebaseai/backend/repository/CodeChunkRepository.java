package com.codebaseai.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.codebaseai.backend.model.CodeChunk;

public interface CodeChunkRepository extends JpaRepository<CodeChunk, UUID> {
    
    List<CodeChunk> findByProjectId(UUID projectId);
    
    @Query(value = """
        SELECT c.*, 1 - (c.embedding <=> CAST(:queryEmbedding AS vector)) as similarity
        FROM chunks c
        WHERE c.project_id = :projectId
        ORDER BY c.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<CodeChunk> findSimilarChunks(
    @Param("projectId") UUID projectId,
    @Param("queryEmbedding") String queryEmbedding,
    @Param("limit") int limit
);
}