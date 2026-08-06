package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.job.dto.JobRequestDto;
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

// 페이지 일괄 저장: FE가 보낸 페이지 최종 상태 전체를 DB 현재 상태와 diff해 수정/추가/삭제/순서변경을 판정·적용한다.
// FE는 최종 상태만 보내고 "무엇이 바뀌었는지"는 서버가 판정 — FE 표시에 의존하면 FE 버그가 그대로 데이터 오염이 된다.
// 변경이 있으면 page_edit_logs에 페이지 전체 before/after 스냅샷 1행을 기록(RLHF용).
@Slf4j
@Service
@RequiredArgsConstructor
public class PageSaveService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final PageEditLogRepository pageEditLogRepository;
    private final S3Service s3Service;

    // TEXT/BRAILLE 두 테이블을 하나의 diff 로직으로 다루기 위한 공통 시야
    private interface Element {
        String elementId();
        String type();
        Integer headingLevel();
        List<String> contents();
        List<String> aiOriginal();
        void updateContents(List<String> contents);
        void updateReadingOrder(int order);
        void markDeleted();
    }

    private record TextView(TextElement el) implements Element {
        public String elementId() { return el.getElementId(); }
        public String type() { return el.getType(); }
        public Integer headingLevel() { return el.getHeadingLevel(); }
        public List<String> contents() { return el.getCurrentContents(); }
        public List<String> aiOriginal() { return el.getOriginalContents(); }
        public void updateContents(List<String> contents) { el.updateCurrentContents(contents); }
        public void updateReadingOrder(int order) { el.updateReadingOrder(order); }
        public void markDeleted() { el.markDeleted(); }
    }

    private record BrailleView(BrailleElement el) implements Element {
        public String elementId() { return el.getElementId(); }
        public String type() { return el.getType(); }
        public Integer headingLevel() { return el.getHeadingLevel(); }
        public List<String> contents() { return el.getCurrentContent(); }
        public List<String> aiOriginal() { return el.getOriginalContent(); }
        public void updateContents(List<String> contents) { el.updateCurrentContent(contents); }
        public void updateReadingOrder(int order) { el.updateReadingOrder(order); }
        public void markDeleted() { el.markDeleted(); }
    }

    @Transactional
    public List<Map<String, Object>> savePage(String userId, String jobId, int pageNo,
                                              List<JobRequestDto.SaveElement> requested) {
        // 1. 본인 Job 검증 — 타인 소유는 존재를 숨기기 위해 404로 통일 (V3 관리 API 관례)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 2. 편집 대상 목록은 mode가 정한다 — a는 text_list, b·c는 braille_text_list.
        //    (mode b의 text_list는 원문 대조용이라 편집 대상이 아니다)
        String mode = pageResult.getMode();
        boolean isText = "a".equals(mode);
        List<Element> live = isText
                ? textElementRepository.findByPageResult(pageResult).stream().map(el -> (Element) new TextView(el)).toList()
                : brailleElementRepository.findByPageResult(pageResult).stream().map(el -> (Element) new BrailleView(el)).toList();
        Map<String, Element> liveById = new LinkedHashMap<>();
        for (Element el : live) liveById.put(el.elementId(), el);

        // 3. 요청 검증 — 모르는 id는 404, 중복 id는 400 (FE 화면 상태가 DB와 어긋난 것)
        Set<String> requestedIds = new HashSet<>();
        for (JobRequestDto.SaveElement item : requested) {
            if (item.id() == null) continue;
            if (!liveById.containsKey(item.id())) throw new CustomException(ErrorCode.ELEMENT_NOT_FOUND);
            if (!requestedIds.add(item.id())) throw new CustomException(ErrorCode.ELEMENT_LIST_MISMATCH);
        }

        // 4. before 스냅샷 (적용 전 상태, 읽기 순서대로)
        Map<String, Map<String, Object>> bboxById = loadBoundingBoxes(pageResult, mode);
        List<Map<String, Object>> before = snapshot(live, bboxById);

        // 5. diff 적용 — 배열 순서가 곧 최종 순서
        List<String> edited = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<Element> finalOrder = new ArrayList<>();
        for (JobRequestDto.SaveElement item : requested) {
            if (item.id() == null) {
                Element neo = createUserBlock(pageResult, item, isText);
                added.add(neo.elementId());
                finalOrder.add(neo);
            } else {
                Element el = liveById.get(item.id());
                if (!item.contents().equals(el.contents())) {
                    el.updateContents(item.contents());
                    edited.add(item.id());
                }
                finalOrder.add(el);
            }
        }
        // 배열에서 빠진 기존 요소 = 삭제 (soft-delete, 행은 학습 데이터로 보존)
        for (Element el : live) {
            if (!requestedIds.contains(el.elementId())) {
                el.markDeleted();
                deleted.add(el.elementId());
            }
        }

        // 순서변경 판정: 살아남은 기존 요소들의 상대 순서가 바뀌었는지
        List<String> beforeOrder = live.stream().map(Element::elementId).filter(requestedIds::contains).toList();
        List<String> afterOrder = finalOrder.stream().map(Element::elementId).filter(id -> !added.contains(id)).toList();
        boolean reordered = !beforeOrder.equals(afterOrder);

        // 6. 변경이 전혀 없으면 로그·카드 날짜를 건드리지 않는다
        if (edited.isEmpty() && added.isEmpty() && deleted.isEmpty() && !reordered) {
            return respond(finalOrder);
        }

        // 7. 살아있는 블록 전체 reading_order = 1..N 재번호 (순서는 서버가 소유)
        for (int i = 0; i < finalOrder.size(); i++) finalOrder.get(i).updateReadingOrder(i + 1);

        // 내용이 바뀌었으므로 카드 날짜·복구 지점 갱신 (같은 트랜잭션이라 별도 저장 불필요)
        job.markContentEdited(pageNo);

        // 8. page_edit_logs 스냅샷 1행 기록 (저장과 같은 트랜잭션)
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("edited", edited);
        changed.put("added", added);
        changed.put("deleted", deleted);
        changed.put("reordered", reordered);
        saveLog(job, pageResult, userId, jobId, pageNo, mode, isText,
                before, snapshot(finalOrder, bboxById), changed);

        log.info("페이지 일괄 저장: jobId={}, pageNo={}, edited={}, added={}, deleted={}, reordered={}",
                jobId, pageNo, edited.size(), added.size(), deleted.size(), reordered);
        return respond(finalOrder);
    }

    // 사용자 작성 새 블록 — 서버가 element_id 발급, original=null(사용자 작성 표식).
    // type은 항상 "text": image·chart_graph 등은 AI가 원본에서 인식해야 존재하는 분류라 사용자가 만들 수 없다.
    private Element createUserBlock(PageResult pageResult, JobRequestDto.SaveElement item, boolean isText) {
        String newId = UUID.randomUUID().toString();
        if (isText) {
            TextElement neo = TextElement.builder()
                    .pageResult(pageResult).elementId(newId).type("text")
                    .contents(item.contents()).isBlocked(false).build();
            neo.markUserAuthored();
            return new TextView(textElementRepository.save(neo));
        }
        BrailleElement neo = BrailleElement.builder()
                .pageResult(pageResult).elementId(newId).type("text")
                .content(item.contents()).isBlocked(false).build();
        neo.markUserAuthored();
        return new BrailleView(brailleElementRepository.save(neo));
    }

    private Map<String, Map<String, Object>> loadBoundingBoxes(PageResult pageResult, String mode) {
        if ("b".equals(mode)) return Map.of();
        Map<String, Map<String, Object>> map = new HashMap<>();
        for (BoundingBox b : boundingBoxRepository.findByPageResult(pageResult)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", b.getX());
            m.put("y", b.getY());
            m.put("x2", b.getX2());
            m.put("y2", b.getY2());
            m.put("type", b.getType());
            map.put(b.getElementId(), m);
        }
        return map;
    }

    // 페이지 상태 스냅샷 — 요소마다 AI 원본까지 담아 이 행만으로 학습 페어(AI 출력 → 인간 최종본)를 만들 수 있게 한다
    private List<Map<String, Object>> snapshot(List<Element> elements, Map<String, Map<String, Object>> bboxById) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Element el : elements) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", el.elementId());
            m.put("type", el.type());
            m.put("heading_level", el.headingLevel());
            m.put("contents", el.contents());
            m.put("origin", el.aiOriginal() == null ? "user" : "ai");
            m.put("ai_original", el.aiOriginal());
            m.put("bounding_box", bboxById.get(el.elementId()));
            list.add(m);
        }
        return list;
    }

    private void saveLog(Job job, PageResult pageResult, String userId, String jobId, int pageNo,
                         String mode, boolean isText, List<Map<String, Object>> before,
                         List<Map<String, Object>> after, Map<String, Object> changed) {
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        String sourcePdfPath = null;
        Integer imageWidth = null;
        Integer imageHeight = null;
        String sourceText = null;
        if ("b".equals(mode)) {
            // 변환에 사용한 원본 한글 텍스트 (S3 .txt)
            sourceText = new String(s3Service.downloadFile(page.getPdfPath()), StandardCharsets.UTF_8);
        } else {
            // mode a, c: 원본 페이지 파일 경로 + 이미지 크기 (요소별 bbox는 스냅샷 안에 포함)
            sourcePdfPath = page.getPdfPath();
            imageWidth = pageResult.getImageWidth();
            imageHeight = pageResult.getImageHeight();
        }

        pageEditLogRepository.save(PageEditLog.builder()
                .userId(UUID.fromString(userId))
                .jobId(jobId)
                .pageNo(pageNo)
                .mode(mode)
                .elementType(isText ? "TEXT" : "BRAILLE")
                .beforeElements(before)
                .afterElements(after)
                .changed(changed)
                .sourcePdfPath(sourcePdfPath)
                .imageWidth(imageWidth)
                .imageHeight(imageHeight)
                .sourceText(sourceText)
                .build());
    }

    // FE 응답 — 최종 배열(요청과 같은 순서). 새 블록은 서버 발급 id가 채워져 FE가 임시 항목을 교체한다.
    // type 등 나머지 요소 정보는 페이지 조회(buildResult)가 담당 — 저장 응답은 id 매핑에 필요한 최소만
    private List<Map<String, Object>> respond(List<Element> finalOrder) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Element el : finalOrder) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", el.elementId());
            m.put("contents", el.contents());
            list.add(m);
        }
        return list;
    }
}
