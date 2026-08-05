package com.semojum.backend.domain.user.controller;

import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.JobSearchRequest;
import com.semojum.backend.domain.user.service.UserService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 마이페이지 작업 목록.
     *
     * <p>범위 — {@code scope=all}이면 폴더 무관 전역(최근 작업·전체 보기),
     * 아니면 {@code folderId}가 있으면 그 폴더 안, 없으면 루트만. 휴지통 항목은 항상 제외.
     */
    @GetMapping("/jobs")
    public ApiResponse<FolderDto.Contents> getMyJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @ModelAttribute JobSearchRequest request
    ) {
        return ApiResponse.success(
                userService.searchEverything(
                        userDetails.getUsername(),
                        // 전체보기·검색은 항상 전역 — 폴더 범위 조회는 /api/folders/{folderId}/contents
                        request.toCondition(null, true),
                        Boolean.TRUE.equals(request.getFavorite()),
                        "oldest".equalsIgnoreCase(request.getSort())));
    }

    // 앱 재시작·네트워크 재연결 시 복구용 — 진행 중(PENDING/IN_PROGRESS) Job 목록.
    // FE는 lastModifiedAt이 가장 최신인 작업의 lastEditedPage로 이동한다.
    @GetMapping("/jobs/active")
    public ApiResponse<List<JobResponseDto.ActiveJob>> getActiveJobs(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(userService.getActiveJobs(userDetails.getUsername()));
    }

    @GetMapping("/jobs/{jobId}/pages/{pageNo}")
    public ApiResponse<JobResponseDto.JobDetail> getJobPage(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(userService.getJobPage(userDetails.getUsername(), jobId, pageNo));
    }
}
