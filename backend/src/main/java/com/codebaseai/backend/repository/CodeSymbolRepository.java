package com.codebaseai.backend.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.model.CodeSymbol;

/**
 * Symbol lookups for the agent are executed against these tables with raw SQL
 * (see ai-service/agent_tools.py); the backend only needs to be able to purge a
 * project's index when a new upload replaces it.
 */
public interface CodeSymbolRepository extends JpaRepository<CodeSymbol, UUID> {

    @Transactional
    @Modifying
    @Query("delete from CodeSymbol s where s.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);
}
