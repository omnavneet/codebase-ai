package com.codebaseai.backend.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.model.CodeReference;

/** See {@link CodeSymbolRepository}: raw SQL reads, backend-side purge only. */
public interface CodeReferenceRepository extends JpaRepository<CodeReference, UUID> {

    @Transactional
    @Modifying
    @Query("delete from CodeReference r where r.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);
}
