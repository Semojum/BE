package com.semojum.backend.domain.trash.controller;

import com.semojum.backend.domain.trash.dto.TrashDto;
import com.semojum.backend.domain.trash.service.TrashService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/trash")
@RequiredArgsConstructor
public class TrashController {

    private final TrashService trashService;

    @GetMapping
    public ApiResponse<TrashDto.Items> list(@AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        return ApiResponse.success(trashService.list(userId));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<TrashDto.RestoreResult> restore(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        return ApiResponse.success(trashService.restore(userId, id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> purge(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        trashService.purge(userId, id);
        return ApiResponse.success(null);
    }
}
