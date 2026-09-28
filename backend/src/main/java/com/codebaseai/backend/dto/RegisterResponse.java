package com.codebaseai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Registration no longer signs the user in: the account exists but stays unusable
 * until the emailed link is opened. The SPA shows {@code message} verbatim and
 * echoes {@code email} so the user knows which inbox to check.
 */
@Data
@AllArgsConstructor
public class RegisterResponse {
    private String message;
    private String email;
}
