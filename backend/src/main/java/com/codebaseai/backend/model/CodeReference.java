package com.codebaseai.backend.model;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * A call site or import found in a file while parsing.
 *
 * <p>Rows are stored raw and resolved against {@code code_symbols} at query
 * time, so the resolution can never go stale. {@code isDynamic} marks references
 * static analysis cannot bind reliably (reflection, {@code getattr}, {@code eval},
 * computed member calls), which callers must report instead of guessing.
 */
@Entity
@Table(name = "code_references")
@Data
public class CodeReference {

    public static final String KIND_CALL = "call";
    public static final String KIND_IMPORT = "import";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    /** Enclosing declared symbol, when the reference sits inside one. */
    @Column(name = "from_symbol")
    private String fromSymbol;

    /** Raw target as written in the source: a call name or an import path. */
    @Column(name = "to_name", nullable = false)
    private String toName;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    private int line;

    @Column(name = "is_dynamic", nullable = false)
    private boolean dynamic;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
