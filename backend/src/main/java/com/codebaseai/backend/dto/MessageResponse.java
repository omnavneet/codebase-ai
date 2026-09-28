package com.codebaseai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Single-message payload used by the verify-email and resend endpoints. */
@Data
@AllArgsConstructor
public class MessageResponse {
    private String message;
}
