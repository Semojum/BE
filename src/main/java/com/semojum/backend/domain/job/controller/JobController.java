package com.semojum.backend.domain.job.controller;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobService;
import com.semojum.backend.domain.job.service.SseService;
import com.semojum.backend.domain.result.service.PageSaveService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final SseService sseService;
    private final JobRepository jobRepository;
    private final PageSaveService pageSaveService;
    private final com.semojum.backend.domain.job.service.JobManageService jobManageService;
    private final com.semojum.backend.domain.job.service.JobCancelService jobCancelService;

    // ===== V3 마이페이지 작업 관리 =====

    // 작업 이름 변경 (원본 파일명은 유지)
    @PatchMapping("/{jobId}")
    public ApiResponse<Map<String, String>> renameJob(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId,
            @Valid @RequestBody JobRequestDto.Rename request
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        var job = jobManageService.rename(userId, jobId, request.fileName());
        return ApiResponse.success(Map.of("jobId", job.getId(), "fileName", job.getOriginalFileName()));
    }

    // 즐겨찾기 토글 (마이페이지 카드)
    @PatchMapping("/{jobId}/favorite")
    public ApiResponse<Map<String, Object>> toggleFavorite(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        boolean isFavorite = jobManageService.toggleFavorite(userId, jobId);
        return ApiResponse.success(Map.of("jobId", jobId, "isFavorite", isFavorite));
    }

    // 작업 일괄 이동 (전체 성공 또는 전체 롤백)
    @PostMapping("/move")
    public ApiResponse<Map<String, Object>> moveJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody JobRequestDto.BulkMove request
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        int moved = jobManageService.moveAll(userId, request.jobIds(), request.targetFolderId());
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("movedCount", moved);
        result.put("targetFolderId", request.targetFolderId());
        return ApiResponse.success(result);
    }

    // 작업 일괄 삭제 → 휴지통 (전체 성공 또는 전체 롤백)
    @PostMapping("/trash")
    public ApiResponse<Map<String, Object>> trashJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody JobRequestDto.BulkTrash request
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        int trashed = jobManageService.trashAll(userId, request.jobIds());
        return ApiResponse.success(Map.of("trashedCount", trashed));
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<JobResponseDto.Create> createJob(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestPart("file") MultipartFile file,
            @RequestParam("mode") String mode,
            // 점자 판면 마지막 줄에 쪽번호를 넣을지 — 업로드 시 선택 (미전송 시 false)
            @RequestParam(value = "insertPageNumber", defaultValue = "false") boolean insertPageNumber
    ) throws Exception {
        return ApiResponse.success(
                jobService.createJob(userDetails.getUsername(), file, mode, insertPageNumber));
    }

    // job 상태 확인 API
    @GetMapping("/{jobId}/status")
    public ApiResponse<JobResponseDto.Status> getJobStatus(
            @PathVariable String jobId
    ) {
        return ApiResponse.success(jobService.getJobStatus(jobId));
    }

    // 변환 취소 — 완료된 페이지까지만 남기고 중단. AI에 이미 들어간 페이지는 마무리 후 저장(취소는 수렴).
    // 이미 끝난 작업이면 멱등(canceled=false, 현재 상태 반환). 확정 여부는 status로 구분(IN_PROGRESS=인플라이트 마무리 중).
    @PostMapping("/{jobId}/cancel")
    public ApiResponse<Map<String, Object>> cancelJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobCancelService.cancel(userDetails.getUsername(), jobId));
    }

    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobEvents(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        return sseService.connect(jobId);
    }

    // 페이지 일괄 저장 — FE가 페이지 최종 상태 전체를 보내면 서버가 diff(수정/추가/삭제/순서)를 판정해 적용.
    // 응답은 최종 배열(요청과 같은 순서) — 새 블록엔 서버 발급 id가 채워진다.
    @PutMapping("/{jobId}/pages/{pageNo}/elements")
    public ApiResponse<List<Map<String, Object>>> savePage(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @RequestBody @Valid JobRequestDto.SavePage request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(pageSaveService.savePage(
                userDetails.getUsername(), jobId, pageNo, request.elements()));
    }

    // 대체 초안 선택 — selected_idx 갱신 + 본문을 해당 초안으로 교체 (-1이면 AI 원본 복귀)
    @PatchMapping("/{jobId}/pages/{pageNo}/elements/{elementId}/draft")
    public ApiResponse<Map<String, Object>> selectDraft(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @PathVariable String elementId,
            @RequestBody @Valid JobRequestDto.SelectDraft request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(pageSaveService.selectDraft(
                userDetails.getUsername(), jobId, pageNo, elementId, request.selectedIdx()));
    }
}