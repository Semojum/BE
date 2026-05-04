package com.semojum.backend.domain.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class JobRequestDto {

    public record Create(
            @NotBlank String mode
    ) {}
}