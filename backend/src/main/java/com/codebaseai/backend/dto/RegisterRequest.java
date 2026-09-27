package com.codebaseai.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    // 8-72: the minimum matches the password-update rule, the maximum is
    // BCrypt's effective input limit.
    @NotBlank
    @Size(min = 8, max = 72)
    private String password;
}