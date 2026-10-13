package com.codebaseai.backend.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.codebaseai.backend.config.AppProperties;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds the S3 client only when {@code app.storage.provider=s3}.
 *
 * <p>Credentials and region come from the AWS default provider chain, so on ECS
 * the task role supplies them - no access keys are ever placed in configuration.
 * The region is set explicitly when configured, which also removes the need for
 * {@code AWS_REGION} to be present in the environment.
 */
@Configuration
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
@Slf4j
public class S3Config {

    @Bean
    public S3Client s3Client(AppProperties properties) {
        var builder = S3Client.builder();
        String region = properties.getS3().getRegion();
        if (StringUtils.hasText(region)) {
            builder.region(Region.of(region));
        }
        // Fail fast at startup with one clear log line instead of an
        // SdkClientException on the first upload/delete: resolve the default
        // chain once here so a missing key set or role is obvious immediately.
        try (var provider = DefaultCredentialsProvider.create()) {
            provider.resolveCredentials();
        } catch (Exception e) {
            String message = "app.storage.provider=s3 but no AWS credentials were found "
                    + "(set AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, a profile, or an "
                    + "instance/task role): " + e.getMessage();
            log.error(message);
            throw new IllegalStateException(message, e);
        }
        return builder.build();
    }
}