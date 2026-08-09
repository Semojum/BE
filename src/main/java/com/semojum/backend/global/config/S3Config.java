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

    // 원본 페이지 PDF의 만료형 서명 URL 발급용 (버킷 공개 읽기 회수 후 원본은 presigned로만 접근)
    @Bean
    public software.amazon.awssdk.services.s3.presigner.S3Presigner s3Presigner(
            @Value("${aws.region}") String region) {
        return software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }
}
