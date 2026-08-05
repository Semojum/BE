package com.semojum.backend.domain.user.controller;

import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.JobSearchCondition;
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

    /**
     * 최근 작업 — 위치 무관 전역 <b>파일만</b> 최신순으로.
     *
     * <p>마이페이지 첫 화면의 "최근 작업" 스트립({@code size=5})과 그 전체보기 화면(S9)이 쓴다.
     * 두 화면 모두 폴더를 그리지 않으므로 폴더를 담지 않는다 — 폴더까지 필요한 화면은
     * {@code GET /api/users/jobs}(검색·전체보기)나 폴더 내부 조회를 쓴다.
     *
     * <p>"최근"이 이름에 박혀 있으므로 정렬은 최신순 고정이다. 필터도 받지 않는다.
     */
    @GetMapping("/jobs/recent")
    public ApiResponse<JobResponseDto.JobList> getRecentJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "30") int size
    ) {
        JobSearchCondition condition = new JobSearchCondition(
                null, true, null, null, null, null, false, cursor, size);
        return ApiResponse.success(userService.getMyJobs(userDetails.getUsername(), condition));
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
