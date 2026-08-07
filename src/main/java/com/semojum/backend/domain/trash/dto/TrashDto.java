package com.semojum.backend.domain.trash.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class TrashDto {

    public record Item(String type, String id, String name, Integer itemCount,
                       LocalDateTime deletedAt, LocalDateTime expiresAt) {}

    public record Items(List<Item> items) {}

    public record RestoreResult(UUID restoredTo) {}
}
