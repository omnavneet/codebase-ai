package com.codebaseai.backend.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.codebaseai.backend.config.AppProperties;

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
public class S3Config {

    @Bean
    public S3Client s3Client(AppProperties properties) {
        var builder = S3Client.builder();
        String region = properties.getS3().getRegion();
        if (StringUtils.hasText(region)) {
            builder.region(Region.of(region));
        }
        return builder.build();
    }
}