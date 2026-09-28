package com.codebaseai.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResendVerificationRequest {
    @NotBlank
    @Email
    @Size(max = 254)
    private String email;
}
