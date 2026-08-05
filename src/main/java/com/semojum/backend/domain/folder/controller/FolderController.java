package com.semojum.backend.domain.folder.controller;

import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.job.dto.JobSearchRequest;
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

    /**
     * 폴더 내부 화면(S2) — 그 폴더의 하위 폴더와 파일을 한 번에 준다.
     *
     * <p>파일 쪽 필터·정렬·커서 파라미터는 목록 조회({@code GET /api/users/jobs})와 동일하다.
     */
    @GetMapping("/{folderId}/contents")
    public ApiResponse<FolderDto.Contents> contents(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID folderId,
            @ModelAttribute JobSearchRequest request) {
        return ApiResponse.success(loadContents(userDetails, folderId, request));
    }

    /** 마이페이지 첫 화면(S1) — 최상위 폴더 + 루트 파일. 위와 같은 응답 구조다. */
    @GetMapping("/contents")
    public ApiResponse<FolderDto.Contents> rootContents(
            @AuthenticationPrincipal UserDetails userDetails,
            @ModelAttribute JobSearchRequest request) {
        return ApiResponse.success(loadContents(userDetails, null, request));
    }

    private FolderDto.Contents loadContents(UserDetails userDetails, UUID folderId,
                                            JobSearchRequest request) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        // 조회 범위는 경로가 정한다 — 쿼리로는 범위를 바꿀 수 없다
        return folderService.contents(
                userId,
                folderId,
                Boolean.TRUE.equals(request.getFavorite()),
                "oldest".equalsIgnoreCase(request.getSort()),
                request.toCondition(folderId, false));
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
