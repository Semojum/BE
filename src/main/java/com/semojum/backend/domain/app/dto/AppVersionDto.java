package com.semojum.backend.domain.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class AppVersionDto {

    public record Register(
            @NotBlank @Size(max = 20) String latestVersion,
            @NotBlank @Size(max = 20) String minSupportedVersion,
            @Size(max = 500) String downloadUrl,
            String releaseNotes,
            @Size(max = 500) String note
    ) {}

    public record Response(
            String latestVersion,
            String minSupportedVersion,
            String downloadUrl,
            String releaseNotes,
            Instant updatedAt
    ) {}

    public record HistoryItem(
            Long id, String latestVersion, String minSupportedVersion,
            String downloadUrl, String releaseNotes, String note, Instant createdAt
    ) {}
}
