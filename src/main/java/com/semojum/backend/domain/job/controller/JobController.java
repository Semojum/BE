package com.semojum.backend.domain.job.controller;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
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
    private final com.semojum.backend.global.util.ClientInfoResolver clientInfoResolver;
    private final SseService sseService;
    private final JobRepository jobRepository;
    private final PageSaveService pageSaveService;
    private final com.semojum.backend.domain.job.service.PageDeleteService pageDeleteService;
    private final com.semojum.backend.domain.job.service.JobManageService jobManageService;
    private final com.semojum.backend.domain.job.service.JobCancelService jobCancelService;
    private final com.semojum.backend.domain.job.service.JobDownloadService jobDownloadService;

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
            @RequestParam(value = "insertPageNumber", defaultValue = "false") boolean insertPageNumber,
            // 꼬리말(묵자, 최대 200자) — 다운로드(brf) 때 점역해 페이지행 가운데 배치. 미전송 시 없음
            @RequestParam(value = "footerText", required = false) String footerText,
            // 조판 옵션 (V30) — 폼 필드로 하나씩 받는다. 안 보낸 항목은 기본값(32칸×26줄·홀수 면 …)
            @RequestParam(value = "cellsPerLine", required = false) Integer cellsPerLine,
            @RequestParam(value = "linesPerPage", required = false) Integer linesPerPage,
            @RequestParam(value = "pageNumberLine", required = false) String pageNumberLine,
            @RequestParam(value = "coverPages", required = false) Integer coverPages,
            @RequestParam(value = "sourcePageStart", required = false) Integer sourcePageStart,
            @RequestParam(value = "braillePageStart", required = false) Integer braillePageStart,
            @RequestParam(value = "showSourcePageNumber", required = false) Boolean showSourcePageNumber,
            @RequestParam(value = "showBraillePageNumber", required = false) Boolean showBraillePageNumber,
            @RequestParam(value = "showChangeLine", required = false) Boolean showChangeLine,
            @RequestParam(value = "footerAlign", required = false) String footerAlign,
            @RequestParam(value = "editScope", required = false) String editScope,
            @RequestParam(value = "advancedAi", required = false) Boolean advancedAi,
            jakarta.servlet.http.HttpServletRequest request
    ) throws Exception {
        LayoutOptions options = new LayoutOptions(cellsPerLine, linesPerPage, pageNumberLine, coverPages,
                sourcePageStart, braillePageStart, showSourcePageNumber, showBraillePageNumber,
                showChangeLine, footerAlign, editScope, advancedAi);
        return ApiResponse.success(
                jobService.createJob(userDetails.getUsername(), file, mode, insertPageNumber, footerText,
                        options, clientInfoResolver.resolve(request)));
    }

    // 업로드 설정 조회 — 조판 옵션 + 꼬리말. 에디터가 작업을 열 때 설정 복원용 (V30)
    @GetMapping("/{jobId}/options")
    public ApiResponse<JobResponseDto.Options> getJobOptions(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobService.getJobOptions(userDetails.getUsername(), jobId));
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

    /**
     * 원본 페이지 영구 삭제 + 뒤 번호 자동 당김 (X-1).
     *
     * <p><b>되돌릴 수 없다.</b> 크레딧은 환불하지 않고 편집 이력은 남긴다(유저 확정).
     * 변환 중이면 JOB4010, 마지막 한 장이면 COMMON4000(작업 자체를 지워야 한다).
     */
    /**
     * 원본 페이지 <b>여러 장</b> 영구 삭제 (X-1 벌크).
     *
     * <p>{@code ?nos=5,6,7,8} — <b>지금 화면에 보이는 번호 그대로</b> 보내면 된다.
     * 서버가 큰 번호부터 지워, 한 장씩 호출할 때 생기는 "지울수록 뒤 번호가 당겨지는" 문제가 없다.
     * 하나라도 없는 쪽이 섞이면 아무것도 지우지 않는다.
     */
    @DeleteMapping("/{jobId}/pages")
    public ApiResponse<Map<String, Object>> deletePages(
            @PathVariable String jobId,
            @RequestParam("nos") List<Integer> nos,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        int remaining = pageDeleteService.deletePages(userDetails.getUsername(), jobId, nos);
        return ApiResponse.success(Map.of("jobId", jobId, "deletedPageNos", nos, "totalPages", remaining));
    }

    @DeleteMapping("/{jobId}/pages/{pageNo}")
    public ApiResponse<Map<String, Object>> deletePage(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        int remaining = pageDeleteService.deletePage(userDetails.getUsername(), jobId, pageNo);
        return ApiResponse.success(Map.of("jobId", jobId, "deletedPageNo", pageNo, "totalPages", remaining));
    }

    // 결과 다운로드 — mode a는 .txt(텍스트 병합), b·c는 .brf(braille-assist 조판).
    // 응답은 JSON 래핑 없이 파일 스트림(Content-Disposition). body는 선택(파일명 지정).
    @PostMapping("/{jobId}/download")
    public org.springframework.http.ResponseEntity<byte[]> downloadJob(
            @PathVariable String jobId,
            @RequestBody(required = false) JobRequestDto.Download request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        var file = jobDownloadService.download(
                userDetails.getUsername(), jobId, request == null ? null : request.fileName());
        String encoded = java.net.URLEncoder.encode(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"download." +
                        (file.fileName().endsWith(".txt") ? "txt" : "brf") + "\"; filename*=UTF-8''" + encoded)
                .header("Content-Type", file.contentType())
                .body(file.content());
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