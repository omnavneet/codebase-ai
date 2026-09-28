package com.codebaseai.backend.controller;

import com.codebaseai.backend.dto.AuthResponse;
import com.codebaseai.backend.dto.LoginRequest;
import com.codebaseai.backend.dto.MessageResponse;
import com.codebaseai.backend.dto.RegisterRequest;
import com.codebaseai.backend.dto.RegisterResponse;
import com.codebaseai.backend.dto.ResendVerificationRequest;
import com.codebaseai.backend.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Deliberately generic: it cannot reveal whether an address exists or is already verified. */
    private static final String RESEND_MESSAGE =
            "If that address still needs verification, a new link is on its way.";

    private static final String VERIFIED_MESSAGE = "Email verified. You can now sign in.";
    
    private final AuthService authService;
    
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }
    
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(authService.login(request, response));
    }
    
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(authService.refresh(refreshToken, response));
    }
    
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken, response);
        return ResponseEntity.ok().build();
    }

    /**
     * Called by the SPA route the verification email links to. Single use: the token
     * is consumed and the link stops working afterwards.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(@RequestParam("token") String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(new MessageResponse(VERIFIED_MESSAGE));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.getEmail());
        return ResponseEntity.ok(new MessageResponse(RESEND_MESSAGE));
    }
}