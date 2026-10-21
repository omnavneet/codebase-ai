package com.codebaseai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-project settings that the frontend can read and write.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProjectSettingsResponse {
    /** Nullable: null when unset, otherwise the stored "answer style" text. */
    private String chatInstructions;
}
