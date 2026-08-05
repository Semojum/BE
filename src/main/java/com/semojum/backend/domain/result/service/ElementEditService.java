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
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<String> editElement(String userId, String jobId, int pageNo,
                                    String elementId, String elementType, List<String> newContents) {
        // 1. 본인 Job 검증 (타인 접근 403)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        // 내용이 바뀌었으므로 카드 날짜 갱신 (같은 트랜잭션이라 별도 저장 불필요)
        job.markContentEdited(pageNo);

        // 2. 해당 페이지 결과
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

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

        // 4. edit_logs 스냅샷 저장 (수정과 같은 트랜잭션, 입력 컨텍스트 포함)
        saveEditLog("EDIT", userId, jobId, job, pageResult, pageNo, elementId, elementPk, type,
                before, newContents, aiOriginal);

        log.info("요소 수정: jobId={}, pageNo={}, elementId={}, type={}", jobId, pageNo, elementId, type);
        return newContents;
    }

    // 블록 삭제(soft-delete): is_deleted=true + 남은 블록 reading_order 재번호 + edit_logs(DELETE) 기록.
    @Transactional
    public void deleteElement(String userId, String jobId, int pageNo, String elementId, String elementType) {
        // 1. 본인 Job 검증(403) + 페이지 결과(404)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        // 내용이 바뀌었으므로 카드 날짜 갱신 (같은 트랜잭션이라 별도 저장 불필요)
        job.markContentEdited(pageNo);
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 2. 요소 조회 → soft-delete → 남은 블록 재번호 (elementType으로 테이블 분기)
        String type = elementType == null ? "" : elementType.toUpperCase();
        List<String> before;
        List<String> aiOriginal;
        UUID elementPk;

        if (type.equals("TEXT")) {
            TextElement el = textElementRepository.findByPageResultAndElementId(pageResult, elementId)
                    .orElseThrow(() -> new CustomException(ErrorCode.ELEMENT_NOT_FOUND));
            if (el.isDeleted()) throw new CustomException(ErrorCode.ELEMENT_NOT_FOUND);
            before = el.getCurrentContents();
            aiOriginal = el.getOriginalContents();
            elementPk = el.getId();
            el.markDeleted();
            renumberText(pageResult);
        } else if (type.equals("BRAILLE")) {
            BrailleElement el = brailleElementRepository.findByPageResultAndElementId(pageResult, elementId)
                    .orElseThrow(() -> new CustomException(ErrorCode.ELEMENT_NOT_FOUND));
            if (el.isDeleted()) throw new CustomException(ErrorCode.ELEMENT_NOT_FOUND);
            before = el.getCurrentContent();
            aiOriginal = el.getOriginalContent();
            elementPk = el.getId();
            el.markDeleted();
            renumberBraille(pageResult);
        } else {
            throw new CustomException(ErrorCode.ELEMENT_INVALID_TYPE);
        }

        // 3. edit_logs(DELETE) 기록: after는 빈 배열(삭제 후 내용 없음)
        saveEditLog("DELETE", userId, jobId, job, pageResult, pageNo, elementId, elementPk, type,
                before, List.of(), aiOriginal);

        log.info("블록 삭제: jobId={}, pageNo={}, elementId={}, type={}", jobId, pageNo, elementId, type);
    }

    // 블록 추가: 사용자가 작성한 새 블록을 afterElementId 뒤(null이면 맨 앞)에 삽입 후 reading_order 1..N 재번호.
    // 서버가 element_id 발급, original=null(사용자 작성 표시), edit_logs(ADD) 기록.
    @Transactional
    public Map<String, Object> addElement(String userId, String jobId, int pageNo,
                                          String elementType, List<String> contents,
                                          String afterElementId, String type) {
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        // 내용이 바뀌었으므로 카드 날짜 갱신 (같은 트랜잭션이라 별도 저장 불필요)
        job.markContentEdited(pageNo);
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        String etype = elementType == null ? "" : elementType.toUpperCase();
        String newElementId = UUID.randomUUID().toString();
        String blockType = (type == null || type.isBlank()) ? "text" : type;
        UUID elementPk;

        if (etype.equals("TEXT")) {
            List<TextElement> current = textElementRepository.findByPageResult(pageResult);
            validateAfter(afterElementId, current.stream().map(TextElement::getElementId).toList());
            TextElement neo = TextElement.builder()
                    .pageResult(pageResult).elementId(newElementId).type(blockType)
                    .contents(contents).isBlocked(false).build();
            neo.markUserAuthored();
            textElementRepository.save(neo);
            List<TextElement> ordered = new ArrayList<>();
            if (afterElementId == null) ordered.add(neo);
            for (TextElement el : current) {
                ordered.add(el);
                if (el.getElementId().equals(afterElementId)) ordered.add(neo);
            }
            for (int i = 0; i < ordered.size(); i++) ordered.get(i).updateReadingOrder(i + 1);
            elementPk = neo.getId();
        } else if (etype.equals("BRAILLE")) {
            List<BrailleElement> current = brailleElementRepository.findByPageResult(pageResult);
            validateAfter(afterElementId, current.stream().map(BrailleElement::getElementId).toList());
            BrailleElement neo = BrailleElement.builder()
                    .pageResult(pageResult).elementId(newElementId).type(blockType)
                    .content(contents).isBlocked(false).build();
            neo.markUserAuthored();
            brailleElementRepository.save(neo);
            List<BrailleElement> ordered = new ArrayList<>();
            if (afterElementId == null) ordered.add(neo);
            for (BrailleElement el : current) {
                ordered.add(el);
                if (el.getElementId().equals(afterElementId)) ordered.add(neo);
            }
            for (int i = 0; i < ordered.size(); i++) ordered.get(i).updateReadingOrder(i + 1);
            elementPk = neo.getId();
        } else {
            throw new CustomException(ErrorCode.ELEMENT_INVALID_TYPE);
        }

        // edit_logs(ADD): before=[], after=contents, aiOriginal=null(사용자 작성)
        saveEditLog("ADD", userId, jobId, job, pageResult, pageNo, newElementId, elementPk, etype,
                List.of(), contents, null);

        log.info("블록 추가: jobId={}, pageNo={}, newElementId={}, type={}, after={}",
                jobId, pageNo, newElementId, etype, afterElementId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", newElementId);
        result.put("contents", contents);
        return result;
    }

    // afterElementId가 null이 아니면 현재(삭제 안 된) 요소 목록에 존재해야 함 (없으면 404)
    private void validateAfter(String afterElementId, List<String> currentIds) {
        if (afterElementId != null && !currentIds.contains(afterElementId)) {
            throw new CustomException(ErrorCode.ELEMENT_NOT_FOUND);
        }
    }

    // 남은(삭제 안 된) 블록을 현재 순서대로 reading_order = 1..N 재번호 (findByPageResult가 삭제분 자동 제외)
    private void renumberText(PageResult pageResult) {
        List<TextElement> survivors = textElementRepository.findByPageResult(pageResult);
        for (int i = 0; i < survivors.size(); i++) survivors.get(i).updateReadingOrder(i + 1);
    }

    private void renumberBraille(PageResult pageResult) {
        List<BrailleElement> survivors = brailleElementRepository.findByPageResult(pageResult);
        for (int i = 0; i < survivors.size(); i++) survivors.get(i).updateReadingOrder(i + 1);
    }

    // edit_logs 스냅샷 저장 (EDIT/DELETE 공용). mode별 입력 컨텍스트까지 자기완결적으로 기록.
    private void saveEditLog(String action, String userId, String jobId, Job job, PageResult pageResult,
                             int pageNo, String elementId, UUID elementPk, String type,
                             List<String> before, List<String> after, List<String> aiOriginal) {
        String mode = pageResult.getMode();
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        String sourcePdfPath = null;
        Integer imageWidth = null;
        Integer imageHeight = null;
        String boundingBoxJson = null;
        String sourceText = null;

        if (mode.equals("b")) {
            // 변환에 사용한 원본 한글 텍스트 (GCS .txt)
            sourceText = new String(s3Service.downloadFile(page.getPdfPath()), StandardCharsets.UTF_8);
        } else {
            // mode a, c: 원본 페이지 파일 경로 + 이미지 크기 + 해당 요소 bbox
            sourcePdfPath = page.getPdfPath();
            imageWidth = pageResult.getImageWidth();
            imageHeight = pageResult.getImageHeight();
            boundingBoxJson = buildBoundingBoxJson(pageResult, elementId);
        }

        EditLog editLog = EditLog.builder()
                .userId(UUID.fromString(userId))
                .jobId(jobId)
                .pageNo(pageNo)
                .elementId(elementPk)
                .elementType(type)
                .mode(mode)
                .action(action)
                .beforeContent(before)
                .afterContent(after)
                .aiOriginalContent(aiOriginal)
                .sourcePdfPath(sourcePdfPath)
                .imageWidth(imageWidth)
                .imageHeight(imageHeight)
                .boundingBox(boundingBoxJson)
                .sourceText(sourceText)
                .build();
        editLogRepository.save(editLog);
    }

    // 블록 순서변경: 그 페이지의 최종 element_id 순서 전체를 받아 reading_order를 1..N으로 재작성.
    // FE는 순서 배열만 보내고 order 숫자는 서버가 결정. 삭제 안 된 요소들의 순열이어야 함(불일치 시 400).
    @Transactional
    public List<String> reorderElements(String userId, String jobId, int pageNo,
                                        String elementType, List<String> orderedElementIds) {
        // 1. 본인 Job 검증 (타인 접근 403)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        // 내용이 바뀌었으므로 카드 날짜 갱신 (같은 트랜잭션이라 별도 저장 불필요)
        job.markContentEdited(pageNo);

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
