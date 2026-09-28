package com.codebaseai.backend.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.codebaseai.backend.config.AppProperties;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private MailService mailService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getMail().setFrom("no-reply@test.local");
        mailService = new MailService(mailSender, properties);
    }

    @Test
    void sendsVerificationLinkAsPlainTextFromTheConfiguredAddress() {
        mailService.sendVerificationEmail(
                "user@example.com",
                "http://localhost:5173/auth/verify-email?token=abc123",
                24);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());

        SimpleMailMessage message = sent.getValue();
        assertEquals("no-reply@test.local", message.getFrom());
        assertArrayEquals(new String[] {"user@example.com"}, message.getTo());
        assertEquals("Verify your Codebase AI account", message.getSubject());
        assertTrue(message.getText().contains("http://localhost:5173/auth/verify-email?token=abc123"));
        assertTrue(message.getText().contains("24 hours"));
    }
}
