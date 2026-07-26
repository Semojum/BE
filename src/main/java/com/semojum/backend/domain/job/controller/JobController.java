package com.semojum.backend.domain.job.controller;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobService;
import com.semojum.backend.domain.job.service.SseService;
import com.semojum.backend.domain.result.service.ElementEditService;
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
    private final ElementEditService elementEditService;

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<JobResponseDto.Create> createJob(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestPart("file") MultipartFile file,
            @RequestParam("mode") String mode
    ) throws Exception {
        return ApiResponse.success(jobService.createJob(userDetails.getUsername(), file, mode));
    }

    // job 상태 확인 API
    @GetMapping("/{jobId}/status")
    public ApiResponse<JobResponseDto.Status> getJobStatus(
            @PathVariable String jobId
    ) {
        return ApiResponse.success(jobService.getJobStatus(jobId));
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

    // 점역사 요소 수정 (current만 갱신, edit_logs 스냅샷 기록)
    @PatchMapping("/{jobId}/pages/{pageNo}/elements/{elementId}")
    public ApiResponse<List<String>> editElement(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @PathVariable String elementId,
            @RequestBody @Valid JobRequestDto.EditElement request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        List<String> updated = elementEditService.editElement(
                userDetails.getUsername(), jobId, pageNo, elementId,
                request.elementType(), request.contents());
        return ApiResponse.success(updated);
    }

    // 블록 순서변경 (전체 순서 배열을 받아 reading_order 1..N 재작성)
    @PatchMapping("/{jobId}/pages/{pageNo}/elements/order")
    public ApiResponse<List<String>> reorderElements(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @RequestBody @Valid JobRequestDto.ReorderElements request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        List<String> ordered = elementEditService.reorderElements(
                userDetails.getUsername(), jobId, pageNo,
                request.elementType(), request.orderedElementIds());
        return ApiResponse.success(ordered);
    }

    // 블록 삭제 (soft-delete + 남은 블록 재번호 + edit_logs DELETE 기록)
    @DeleteMapping("/{jobId}/pages/{pageNo}/elements/{elementId}")
    public ApiResponse<Void> deleteElement(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @PathVariable String elementId,
            @RequestParam String elementType,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        elementEditService.deleteElement(
                userDetails.getUsername(), jobId, pageNo, elementId, elementType);
        return ApiResponse.success(null);
    }

    // 블록 추가 (사용자 작성 새 블록을 afterElementId 뒤에 삽입 + 재번호 + edit_logs ADD 기록)
    @PostMapping("/{jobId}/pages/{pageNo}/elements")
    public ApiResponse<Map<String, Object>> addElement(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @RequestBody @Valid JobRequestDto.AddElement request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Map<String, Object> created = elementEditService.addElement(
                userDetails.getUsername(), jobId, pageNo,
                request.elementType(), request.contents(), request.afterElementId(), request.type());
        return ApiResponse.success(created);
    }
}