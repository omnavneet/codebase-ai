package com.codebaseai.backend.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.dto.UpdatePasswordRequest;
import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.RefreshTokenRepository;
import com.codebaseai.backend.repository.UserRepository;

class UserControllerTest {

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordEncoder passwordEncoder;
    private UserController controller;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        controller = new UserController(userRepository, refreshTokenRepository, passwordEncoder);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash("old-hash");
        user.setVerified(true);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getId().toString(), null, List.of()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static UpdatePasswordRequest request(String currentPassword, String newPassword) {
        UpdatePasswordRequest request = new UpdatePasswordRequest();
        request.setCurrentPassword(currentPassword);
        request.setNewPassword(newPassword);
        return request;
    }

    @Test
    void updatePasswordStoresNewHashAndRevokesRefreshTokens() {
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hash");

        controller.updatePassword(request("current-password", "new-password-123"));

        assertEquals("new-hash", user.getPasswordHash());
        verify(userRepository).save(user);
        verify(refreshTokenRepository).deleteByUserId(user.getId());
    }

    @Test
    void wrongCurrentPasswordChangesNothing() {
        when(passwordEncoder.matches("wrong-password", "old-hash")).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.updatePassword(request("wrong-password", "new-password-123")));
        assertEquals(400, exception.getStatusCode().value());

        assertEquals("old-hash", user.getPasswordHash());
        verify(userRepository, never()).save(any(User.class));
        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

}
