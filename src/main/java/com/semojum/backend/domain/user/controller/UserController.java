package com.semojum.backend.domain.user.controller;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.user.service.UserService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 마이페이지.
 *
 * <p><b>V3.1부터 작업 목록을 주지 않는다</b> — 작업물은 로컬 드라이브에 {@code .smj}로 보관하고,
 * 마이페이지는 사용량·과금·계정·문의만 보여준다. 전체보기·검색({@code /jobs})과
 * 최근 작업({@code /jobs/recent})은 폴더·휴지통과 함께 폐기됐다.
 *
 * <p>여기 남은 둘은 목록이 아니다 — 하나는 앱 복구용, 하나는 에디터가 쪽을 읽는 경로다.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

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
