package com.semojum.backend.domain.result.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.gcs.GcsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 점역사 요소 수정: current만 갱신(original 보존) + edit_logs 스냅샷 기록(RLHF용)
@Slf4j
@Service
@RequiredArgsConstructor
public class ElementEditService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final EditLogRepository editLogRepository;
    private final GcsService gcsService;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<String> editElement(String userId, String jobId, int pageNo,
                                    String elementId, String elementType, List<String> newContents) {
        // 1. 본인 Job 검증 (타인 접근 403)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        // 2. 해당 페이지 결과
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        String mode = pageResult.getMode();

        // 3. 요소 조회 + current 갱신 (elementType으로 테이블 분기)
        String type = elementType == null ? "" : elementType.toUpperCase();
        UUID elementPk;
        List<String> before;
        List<String> aiOriginal;

        if (type.equals("TEXT")) {
            TextElement el = textElementRepository.findByPageResultAndElementId(pageResult, elementId)
                    .orElseThrow(() -> new CustomException(ErrorCode.ELEMENT_NOT_FOUND));
            before = el.getCurrentContents();
            aiOriginal = el.getOriginalContents();
            el.updateCurrentContents(newContents);
            elementPk = el.getId();
        } else if (type.equals("BRAILLE")) {
            BrailleElement el = brailleElementRepository.findByPageResultAndElementId(pageResult, elementId)
                    .orElseThrow(() -> new CustomException(ErrorCode.ELEMENT_NOT_FOUND));
            before = el.getCurrentContent();
            aiOriginal = el.getOriginalContent();
            el.updateCurrentContent(newContents);
            elementPk = el.getId();
        } else {
            throw new CustomException(ErrorCode.ELEMENT_INVALID_TYPE);
        }

        // 4. 입력 컨텍스트 스냅샷 (mode별)
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        String sourcePdfPath = null;
        Integer imageWidth = null;
        Integer imageHeight = null;
        String boundingBoxJson = null;
        String sourceText = null;

        if (mode.equals("b")) {
            // 변환에 사용한 원본 한글 텍스트 (GCS .txt)
            sourceText = new String(gcsService.downloadFile(page.getPdfPath()), StandardCharsets.UTF_8);
        } else {
            // mode a, c: 원본 페이지 파일 경로 + 이미지 크기 + 해당 요소 bbox
            sourcePdfPath = page.getPdfPath();
            imageWidth = pageResult.getImageWidth();
            imageHeight = pageResult.getImageHeight();
            boundingBoxJson = buildBoundingBoxJson(pageResult, elementId);
        }

        // 5. edit_logs 스냅샷 저장 (수정과 같은 트랜잭션)
        EditLog editLog = EditLog.builder()
                .userId(UUID.fromString(userId))
                .jobId(jobId)
                .pageNo(pageNo)
                .elementId(elementPk)
                .elementType(type)
                .mode(mode)
                .beforeContent(before)
                .afterContent(newContents)
                .aiOriginalContent(aiOriginal)
                .sourcePdfPath(sourcePdfPath)
                .imageWidth(imageWidth)
                .imageHeight(imageHeight)
                .boundingBox(boundingBoxJson)
                .sourceText(sourceText)
                .build();
        editLogRepository.save(editLog);

        log.info("요소 수정: jobId={}, pageNo={}, elementId={}, type={}", jobId, pageNo, elementId, type);
        return newContents;
    }

    // 블록 순서변경: 그 페이지의 최종 element_id 순서 전체를 받아 reading_order를 1..N으로 재작성.
    // FE는 순서 배열만 보내고 order 숫자는 서버가 결정. 삭제 안 된 요소들의 순열이어야 함(불일치 시 400).
    @Transactional
    public List<String> reorderElements(String userId, String jobId, int pageNo,
                                        String elementType, List<String> orderedElementIds) {
        // 1. 본인 Job 검증 (타인 접근 403)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        // 2. 해당 페이지 결과
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 3. elementType 분기 → 현재 살아있는(is_deleted=false) 요소 id→엔티티 맵 구성 후 순서대로 재번호
        String type = elementType == null ? "" : elementType.toUpperCase();
        if (type.equals("TEXT")) {
            Map<String, TextElement> map = new HashMap<>();
            for (TextElement el : textElementRepository.findByPageResult(pageResult)) {
                map.put(el.getElementId(), el);
            }
            validateOrder(orderedElementIds, map.keySet());
            for (int i = 0; i < orderedElementIds.size(); i++) {
                map.get(orderedElementIds.get(i)).updateReadingOrder(i + 1);
            }
        } else if (type.equals("BRAILLE")) {
            Map<String, BrailleElement> map = new HashMap<>();
            for (BrailleElement el : brailleElementRepository.findByPageResult(pageResult)) {
                map.put(el.getElementId(), el);
            }
            validateOrder(orderedElementIds, map.keySet());
            for (int i = 0; i < orderedElementIds.size(); i++) {
                map.get(orderedElementIds.get(i)).updateReadingOrder(i + 1);
            }
        } else {
            throw new CustomException(ErrorCode.ELEMENT_INVALID_TYPE);
        }

        log.info("블록 순서변경: jobId={}, pageNo={}, type={}, count={}", jobId, pageNo, type, orderedElementIds.size());
        return orderedElementIds;
    }

    // 순서 목록이 현재 페이지 요소 집합과 정확히 일치(같은 원소들의 순열)하는지 검증. 누락/중복/미지의 id면 400.
    private void validateOrder(List<String> orderedElementIds, Set<String> currentIds) {
        if (orderedElementIds.size() != currentIds.size()
                || !new HashSet<>(orderedElementIds).equals(currentIds)) {
            throw new CustomException(ErrorCode.ELEMENT_ORDER_MISMATCH);
        }
    }

    // 해당 요소(elementId)의 bbox를 JSON 문자열로 직렬화 (mode a/c). 없으면 null.
    private String buildBoundingBoxJson(PageResult pageResult, String elementId) {
        return boundingBoxRepository.findByPageResult(pageResult).stream()
                .filter(b -> elementId.equals(b.getElementId()))
                .findFirst()
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("x", b.getX());
                    m.put("y", b.getY());
                    m.put("x2", b.getX2());
                    m.put("y2", b.getY2());
                    m.put("type", b.getType());
                    try {
                        return objectMapper.writeValueAsString(m);
                    } catch (Exception e) {
                        log.warn("boundingBox 직렬화 실패: elementId={}, {}", elementId, e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }
}
