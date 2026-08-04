package com.semojum.backend.domain.user.controller;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.user.service.UserService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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
    public ApiResponse<JobResponseDto.JobList> getMyJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> mode,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "30") int size
    ) {
        JobSearchCondition condition = new JobSearchCondition(
                folderId, "all".equalsIgnoreCase(scope), search,
                status, mode, favorite,
                "oldest".equalsIgnoreCase(sort), cursor, size);
        return ApiResponse.success(userService.getMyJobs(userDetails.getUsername(), condition));
    }

    // 앱 재시작·네트워크 재연결 시 복구용 — 진행 중(PENDING/IN_PROGRESS) Job 목록.
    // FE는 lastModifiedAt이 가장 최신인 작업의 lastEditedPage로 이동한다.
    @GetMapping("/jobs/active")
    public ApiResponse<List<JobResponseDto.JobCard>> getActiveJobs(
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
