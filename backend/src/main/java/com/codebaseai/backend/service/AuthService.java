package com.codebaseai.backend.service;

import com.codebaseai.backend.dto.AuthResponse;
import com.codebaseai.backend.dto.LoginRequest;
import com.codebaseai.backend.dto.RegisterRequest;
import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    
    public AuthResponse register(RegisterRequest request) {
        // Check if email exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }
        
        // Create user
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        
        userRepository.save(user);
        
        // Generate tokens
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        
        return new AuthResponse(accessToken, user.getEmail(), user.getId().toString());
    }
    
    public AuthResponse login(LoginRequest request) {
        // Find user
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));
        
        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Invalid credentials");
        }
        
        // Generate tokens
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        
        return new AuthResponse(accessToken, user.getEmail(), user.getId().toString());
    }
}