package com.codebaseai.backend.dto;

import java.util.UUID;

/**
 * Status of one indexing job, as returned by the job status and cancel endpoints.
 *
 * <p>Mirrors the columns the frontend actually renders: a lifecycle status, a
 * percentage for the progress bar, and an error message for the failed case.
 */
public record IndexingJobResponse(
        UUID id,
        UUID projectId,
        String status,
        int progressPercent,
        int attempts,
        String errorMessage) {
}
