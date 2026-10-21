package com.codebaseai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mutable per-project settings.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProjectSettingsRequest {
    private String chatInstructions;
}
