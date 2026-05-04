package com.semojum.backend.domain.job.dto;

public class JobResponseDto {

    public record Create(
            String jobId,
            String mode,
            int totalPages,
            String status
    ) {}
}