package com.codebaseai.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.model.CodeChunk;

public interface CodeChunkRepository extends JpaRepository<CodeChunk, UUID> {

    @Transactional
    @Modifying
    @Query("delete from CodeChunk c where c.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);

    @Query(value = """
        WITH generation_chunks AS MATERIALIZED (
          SELECT c.* FROM chunks c JOIN files f ON f.id = c.file_id
          WHERE c.project_id = :projectId
            AND f.generation_id IS NOT DISTINCT FROM CAST(:generationId AS uuid)
        )
        SELECT c.*, 1 - (c.embedding <=> CAST(:queryEmbedding AS vector)) as similarity
        FROM generation_chunks c
        ORDER BY c.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<CodeChunk> findSimilarChunks(
    @Param("projectId") UUID projectId,
    @Param("generationId") UUID generationId,
    @Param("queryEmbedding") String queryEmbedding,
    @Param("limit") int limit
);
}
