package com.semojum.backend.domain.folder.controller;

import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.folder.service.FolderService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;

    @PostMapping
    public ApiResponse<FolderDto.Response> create(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody FolderDto.Create request
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        return ApiResponse.success(folderService.create(userId, request));
    }

    @PatchMapping("/{folderId}")
    public ApiResponse<FolderDto.Response> update(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID folderId,
            @RequestBody FolderDto.Update request
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        return ApiResponse.success(folderService.update(userId, folderId, request));
    }

    @DeleteMapping("/{folderId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID folderId
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        folderService.moveToTrash(userId, folderId);
        return ApiResponse.success(null);
    }

    // 즐겨찾기 토글 (마이페이지 폴더 카드)
    @PatchMapping("/{folderId}/favorite")
    public ApiResponse<Map<String, Object>> toggleFavorite(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID folderId
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        boolean isFavorite = folderService.toggleFavorite(userId, folderId);
        return ApiResponse.success(Map.of("folderId", folderId.toString(), "isFavorite", isFavorite));
    }

    // 폴더 내부 화면(S2)·첫 화면 목록 — parentId 생략 시 최상위 폴더
    @GetMapping
    public ApiResponse<FolderDto.Items> children(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false, defaultValue = "latest") String sort) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        return ApiResponse.success(folderService.children(userId, parentId,
                Boolean.TRUE.equals(favorite), "oldest".equalsIgnoreCase(sort)));
    }

    @GetMapping("/tree")
    public ApiResponse<FolderDto.Tree> tree(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false, defaultValue = "latest") String sort) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        return ApiResponse.success(folderService.tree(userId,
                Boolean.TRUE.equals(favorite), "oldest".equalsIgnoreCase(sort)));
    }
}
