package com.codebaseai.backend.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.codebaseai.backend.config.AppProperties;

import lombok.RequiredArgsConstructor;

/**
 * Thin adapter over {@link JavaMailSender}. Local development points it at MailHog,
 * production at an SMTP provider (SES, SendGrid, ...); nothing here knows about a
 * specific vendor, only {@code spring.mail.*} configuration changes.
 *
 * <p>Delivery failures surface as {@link org.springframework.mail.MailException} and
 * are translated by the caller, so this class stays a pure adapter.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    public void sendVerificationEmail(String toEmail, String verificationUrl, int ttlHours) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMail().getFrom());
        message.setTo(toEmail);
        message.setSubject("Verify your Codebase AI account");
        message.setText("""
                Welcome to Codebase AI!

                Confirm your email address by opening this link:
                %s

                The link expires in %d hours. If you did not create this account,
                you can ignore this email.
                """.formatted(verificationUrl, ttlHours));
        mailSender.send(message);
    }
}
