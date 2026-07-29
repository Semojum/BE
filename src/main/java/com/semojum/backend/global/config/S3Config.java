package com.semojum.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

    // 자격 증명은 DefaultCredentialsProvider 체인 사용:
    // 로컬 = aws configure 프로파일, EC2 = 인스턴스 IAM Role (키 파일 불필요)
    @Bean
    public S3Client s3Client(@Value("${aws.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}
