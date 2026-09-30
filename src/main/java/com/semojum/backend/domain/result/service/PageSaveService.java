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
    private final BrailleRetranslator brailleRetranslator;

    /** 편집 대상 패널 — mode c(3패널)만 둘 다 편집할 수 있다 */
    public static final String TARGET_TEXT = "text";
    public static final String TARGET_BRAILLE = "braille";

    // AI가 시각 요소 본문에 붙여 보내는 점역자주 마커 (mode a 초안 선택 시 형태 보존용)
    private static final String TN_OPEN = "<!점역자주>";
    private static final String TN_CLOSE = "<!/점역자주>";

    // TEXT/BRAILLE 두 테이블을 하나의 diff 로직으로 다루기 위한 공통 시야
    private interface Element {
        String elementId();
        String type();
        Integer headingLevel();
        List<String> contents();
        List<String> aiOriginal();
        List<Map<String, Object>> drafts();
        Integer selectedIdx();
        void updateContents(List<String> contents);
        void updateReadingOrder(int order);
        void updateSelectedIdx(Integer idx);
        void markDeleted();
    }

    private record TextView(TextElement el) implements Element {
        public String elementId() { return el.getElementId(); }
        public String type() { return el.getType(); }
        public Integer headingLevel() { return el.getHeadingLevel(); }
        public List<String> contents() { return el.getCurrentContents(); }
        public List<String> aiOriginal() { return el.getOriginalContents(); }
        public List<Map<String, Object>> drafts() { return el.getDrafts(); }
        public Integer selectedIdx() { return el.getSelectedIdx(); }
        public void updateContents(List<String> contents) { el.updateCurrentContents(contents); }
        public void updateReadingOrder(int order) { el.updateReadingOrder(order); }
        public void updateSelectedIdx(Integer idx) { el.updateSelectedIdx(idx); }
        public void markDeleted() { el.markDeleted(); }
    }

    private record BrailleView(BrailleElement el) implements Element {
        public String elementId() { return el.getElementId(); }
        public String type() { return el.getType(); }
        public Integer headingLevel() { return el.getHeadingLevel(); }
        public List<String> contents() { return el.getCurrentContent(); }
        public List<String> aiOriginal() { return el.getOriginalContent(); }
        public List<Map<String, Object>> drafts() { return el.getDrafts(); }
        public Integer selectedIdx() { return el.getSelectedIdx(); }
        public void updateContents(List<String> contents) { el.updateCurrentContent(contents); }
        public void updateReadingOrder(int order) { el.updateReadingOrder(order); }
        public void updateSelectedIdx(Integer idx) { el.updateSelectedIdx(idx); }
        public void markDeleted() { el.markDeleted(); }
    }

    /** 편집 대상을 지정하지 않는 구 호출 — mode 기본 대상(a=text, b·c=braille) */
    @Transactional
    public List<Map<String, Object>> savePage(String userId, String jobId, int pageNo,
                                              List<JobRequestDto.SaveElement> requested) {
        return savePage(userId, jobId, pageNo, null, requested, Map.of());
    }

    /**
     * 텍스트 패널 저장이 재점역해야 할 텍스트 본문들 (트랜잭션 밖 AI 호출을 위한 사전 조회).
     * mode c의 텍스트 저장에서 <b>내용이 바뀐 요소와 새 블록</b>만 — 그 외엔 빈 집합.
     */
    @Transactional(readOnly = true)
    public Set<String> textsToRetranslate(String userId, String jobId, int pageNo, String target,
                                          List<JobRequestDto.SaveElement> requested) {
        jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        String mode = pageResult.getMode();
        if (!syncsBraille(mode, resolveTarget(mode, target))) return Set.of();

        Map<String, List<String>> current = new HashMap<>();
        for (TextElement el : textElementRepository.findByPageResult(pageResult)) {
            current.put(el.getElementId(), el.getCurrentContents());
        }
        Set<String> texts = new java.util.LinkedHashSet<>();
        for (JobRequestDto.SaveElement item : requested) {
            if (item.id() == null || !item.contents().equals(current.get(item.id()))) {
                texts.add(joined(item.contents()));
            }
        }
        return texts;
    }

    /**
     * @param target        편집 패널 — null이면 mode 기본값(a=text, b·c=braille). c만 text·braille 둘 다 받는다
     * @param lineBraille   mode c 텍스트 저장의 재점역 결과(텍스트 줄 → 점자 줄). 트랜잭션 밖에서 미리 구해 온다
     */
    @Transactional
    public List<Map<String, Object>> savePage(String userId, String jobId, int pageNo, String target,
                                              List<JobRequestDto.SaveElement> requested,
                                              Map<String, String> lineBraille) {
        // 1. 본인 Job 검증 — 타인 소유는 존재를 숨기기 위해 404로 통일 (V3 관리 API 관례)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 2. 편집 대상 목록 — a는 text_list, b는 braille_text_list(text_list는 원문 대조용이라 편집 불가),
        //    c(3패널)는 요청이 고른 쪽. 기본은 braille(3패널 이전 c와 같다)
        String mode = pageResult.getMode();
        String resolved = resolveTarget(mode, target);
        boolean isText = TARGET_TEXT.equals(resolved);
        boolean syncBraille = syncsBraille(mode, resolved);
        List<Element> live = loadLive(pageResult, isText);
        Map<String, Element> liveById = new LinkedHashMap<>();
        for (Element el : live) liveById.put(el.elementId(), el);

        // 3. 요청 검증 — 모르는 id는 404, 중복 id는 400 (FE 화면 상태가 DB와 어긋난 것)
        Set<String> requestedIds = new HashSet<>();
        for (JobRequestDto.SaveElement item : requested) {
            if (item.id() == null) continue;
            if (!liveById.containsKey(item.id())) throw new CustomException(ErrorCode.ELEMENT_NOT_FOUND);
            if (!requestedIds.add(item.id())) throw new CustomException(ErrorCode.ELEMENT_LIST_MISMATCH);
        }

        // 바꾸기 전 텍스트 — 점자 동기화가 안 바뀐 줄을 찾아 AI 원래 점자를 재사용하는 데 쓴다
        Map<String, List<String>> prevContents = new HashMap<>();
        for (Element el : live) prevContents.put(el.elementId(), el.contents());

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
            return syncBraille ? respond(finalOrder, currentBraille(pageResult)) : respond(finalOrder);
        }

        // 7. 살아있는 블록 전체 reading_order = 1..N 재번호 (순서는 서버가 소유)
        for (int i = 0; i < finalOrder.size(); i++) finalOrder.get(i).updateReadingOrder(i + 1);

        // 7-1. mode c 텍스트 저장 → 같은 id 점자 요소를 따라 맞춘다(재점역·추가·삭제·순서)
        Map<String, Object> brailleSynced = null;
        Map<String, List<String>> brailleById = null;
        if (syncBraille) {
            brailleSynced = new LinkedHashMap<>();
            brailleById = syncBraille(pageResult, finalOrder, prevContents, edited, added, deleted,
                    lineBraille == null ? Map.of() : lineBraille, brailleSynced);
        }

        // 내용이 바뀌었으므로 카드 날짜·복구 지점 갱신 (같은 트랜잭션이라 별도 저장 불필요)
        job.markContentEdited(pageNo);

        // 8. page_edit_logs 스냅샷 1행 기록 (저장과 같은 트랜잭션)
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("edited", edited);
        changed.put("added", added);
        changed.put("deleted", deleted);
        changed.put("reordered", reordered);
        if (brailleSynced != null) changed.put("braille_synced", brailleSynced);
        saveLog(job, pageResult, userId, jobId, pageNo, mode, isText,
                before, snapshot(finalOrder, bboxById), changed);

        log.info("페이지 일괄 저장: jobId={}, pageNo={}, target={}, edited={}, added={}, deleted={}, reordered={}{}",
                jobId, pageNo, resolved, edited.size(), added.size(), deleted.size(), reordered,
                brailleSynced == null ? "" : ", 점자 동기화=" + brailleSynced);
        return brailleById == null ? respond(finalOrder) : respond(finalOrder, brailleById);
    }

    /**
     * 편집 패널 결정. 지정이 없으면 mode 기본값, 모드가 그 패널을 편집할 수 없으면 COMMON4000.
     * a는 결과물이 텍스트뿐, b의 텍스트는 원문 대조용이라 편집 불가. c는 둘 다(3패널).
     */
    static String resolveTarget(String mode, String target) {
        if (target == null || target.isBlank()) {
            return "a".equals(mode) ? TARGET_TEXT : TARGET_BRAILLE;
        }
        boolean ok = switch (target) {
            case TARGET_TEXT -> "a".equals(mode) || "c".equals(mode);
            case TARGET_BRAILLE -> !"a".equals(mode);
            default -> false;
        };
        if (!ok) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        return target;
    }

    /** 텍스트를 고치면 점자가 따라가는 경우 — 3패널(c)의 텍스트 저장뿐 */
    private static boolean syncsBraille(String mode, String resolvedTarget) {
        return "c".equals(mode) && TARGET_TEXT.equals(resolvedTarget);
    }

    private static String joined(List<String> contents) {
        return contents == null ? "" : String.join("\n", contents);
    }

    /**
     * 텍스트 최종 상태에 맞춰 점자 요소를 고친다 (mode c, 같은 id 1:1).
     * <ul>
     *   <li>바뀐·새 텍스트 → 같은 id 점자의 current 교체(없으면 같은 id로 새로 만든다). 안 바뀐 줄은 AI 원래
     *       점자를 쓰고 바뀐 줄만 재점역한다({@link BrailleRetranslator#compose}). original은 보존</li>
     *   <li>지운 텍스트 → 같은 id 점자도 soft-delete</li>
     *   <li>순서 → 짝 있는 점자는 텍스트 순서를 따르고, 점자 패널에서만 추가한 블록(짝 없음)은
     *       원래 바로 앞에 있던 짝 있는 블록 뒤에 그대로 붙는다</li>
     * </ul>
     * @return 점자 요소 id → 최종 current (응답의 brailleContents)
     */
    private Map<String, List<String>> syncBraille(PageResult pageResult, List<Element> textFinal,
                                                  Map<String, List<String>> prevContents,
                                                  List<String> edited, List<String> added, List<String> deleted,
                                                  Map<String, String> lineBraille, Map<String, Object> summary) {
        List<BrailleElement> brailleLive = brailleElementRepository.findByPageResult(pageResult);
        Map<String, BrailleElement> byId = new LinkedHashMap<>();
        for (BrailleElement b : brailleLive) byId.putIfAbsent(b.getElementId(), b);

        List<String> bEdited = new ArrayList<>();
        List<String> bAdded = new ArrayList<>();
        List<String> bDeleted = new ArrayList<>();

        for (String id : deleted) {
            BrailleElement b = byId.remove(id);
            if (b != null) {
                b.markDeleted();
                bDeleted.add(id);
            }
        }

        Map<String, Element> textById = new HashMap<>();
        for (Element t : textFinal) textById.put(t.elementId(), t);
        List<String> changedIds = new ArrayList<>(edited);
        changedIds.addAll(added);
        for (String id : changedIds) {
            Element t = textById.get(id);
            String text = joined(t.contents());
            String prevText = prevContents.containsKey(id) ? joined(prevContents.get(id)) : null;
            Map<String, String> lines = lineBraille;
            if (BrailleRetranslator.hasUntranslated(text, lineBraille)) {
                // 사전 조회와 저장 사이에 내용이 바뀐 경우(동시 저장) — 여기서 한 번 더 부른다
                lines = new HashMap<>(lineBraille);
                lines.putAll(brailleRetranslator.translateLines(List.of(text)));
            }
            BrailleElement b = byId.get(id);
            if (b != null) {
                b.updateCurrentContent(BrailleRetranslator.compose(text, prevText, b.getCurrentContent(), lines));
                bEdited.add(id);
            } else {
                BrailleElement neo = BrailleElement.builder()
                        .pageResult(pageResult).elementId(id)
                        .type(t.type()).headingLevel(t.headingLevel())
                        .content(BrailleRetranslator.compose(text, null, null, lines)).isBlocked(false).build();
                neo.markUserAuthored();
                byId.put(id, brailleElementRepository.save(neo));
                bAdded.add(id);
            }
        }

        // 순서: 짝 없는 점자 블록은 원래 바로 앞의 "살아남은 짝 있는 블록"에 매달아 둔다("" = 맨 앞)
        Set<String> textIds = textById.keySet();
        Map<String, List<BrailleElement>> anchored = new HashMap<>();
        String anchor = "";
        for (BrailleElement b : brailleLive) {
            if (bDeleted.contains(b.getElementId()) && !byId.containsKey(b.getElementId())) continue;
            if (textIds.contains(b.getElementId())) anchor = b.getElementId();
            else anchored.computeIfAbsent(anchor, k -> new ArrayList<>()).add(b);
        }
        List<BrailleElement> order = new ArrayList<>(anchored.getOrDefault("", List.of()));
        for (Element t : textFinal) {
            BrailleElement b = byId.get(t.elementId());
            if (b != null) order.add(b);
            order.addAll(anchored.getOrDefault(t.elementId(), List.of()));
        }
        for (int i = 0; i < order.size(); i++) order.get(i).updateReadingOrder(i + 1);

        summary.put("edited", bEdited);
        summary.put("added", bAdded);
        summary.put("deleted", bDeleted);

        Map<String, List<String>> out = new HashMap<>();
        for (BrailleElement b : order) out.put(b.getElementId(), b.getCurrentContent());
        return out;
    }

    private Map<String, List<String>> currentBraille(PageResult pageResult) {
        Map<String, List<String>> out = new HashMap<>();
        for (BrailleElement b : brailleElementRepository.findByPageResult(pageResult)) {
            out.putIfAbsent(b.getElementId(), b.getCurrentContent());
        }
        return out;
    }

    /**
     * 대체 초안 선택 — AI가 준 drafts 중 하나를 골라 본문(current)을 교체하고 selected_idx를 갱신한다.
     * {@code selectedIdx = -1}이면 선택을 해제하고 AI 원본(original)으로 되돌린다.
     *
     * <p>본문에 넣을 값은 모드가 정한다:
     * <ul>
     *   <li>mode b·c: 결과물이 점자라 {@code draft.contents}(점자 통 문자열)를 그대로 쓴다</li>
     *   <li>mode a: 결과물이 텍스트라 {@code draft.contents}가 비어 있다 → {@code draft.text}를 쓰되,
     *       기존 본문이 점역자주 마커로 감싸여 있었으면 새 텍스트도 같은 마커로 감싼다
     *       (마커 규약을 AI 출력에서 그대로 따라가므로 별도 합의가 필요 없다)</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> selectDraft(String userId, String jobId, int pageNo,
                                           String elementId, int selectedIdx) {
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        String mode = pageResult.getMode();
        boolean isText = "a".equals(mode);
        List<Element> live = loadLive(pageResult, isText);
        Element el = live.stream()
                .filter(e -> e.elementId().equals(elementId))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.ELEMENT_NOT_FOUND));

        List<Map<String, Object>> drafts = el.drafts();
        if (drafts == null || drafts.isEmpty() || selectedIdx < -1 || selectedIdx >= drafts.size()) {
            // 초안이 없는 요소이거나 범위 밖 번호
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        Map<String, Map<String, Object>> bboxById = loadBoundingBoxes(pageResult, mode);
        List<Map<String, Object>> before = snapshot(live, bboxById);
        Integer prevIdx = el.selectedIdx();

        List<String> newContents = selectedIdx < 0
                ? (el.aiOriginal() == null ? List.of() : el.aiOriginal())
                : draftContents(drafts.get(selectedIdx), el.contents());
        el.updateContents(newContents);
        el.updateSelectedIdx(selectedIdx);

        // mode c(3패널): 텍스트 패널의 같은 id 요소도 같은 초안으로 맞춘다 — 피커가 보여 준 묵자가 곧
        // 그 초안의 텍스트다(drafts[i].text). 재점역은 필요 없다(초안이 점자를 이미 들고 있다)
        List<String> textContents = null;
        if ("c".equals(mode)) {
            TextElement textEl = textElementRepository.findByPageResult(pageResult).stream()
                    .filter(t -> t.getElementId().equals(elementId))
                    .findFirst().orElse(null);
            if (textEl != null) {
                textContents = selectedIdx < 0
                        ? (textEl.getOriginalContents() == null ? List.of() : textEl.getOriginalContents())
                        : draftText(drafts.get(selectedIdx), textEl.getCurrentContents());
                textEl.updateCurrentContents(textContents);
                textEl.updateSelectedIdx(selectedIdx);
            }
        }

        // 본문이 바뀌었으므로 카드 날짜·복구 지점 갱신 (일괄 저장과 동일)
        job.markContentEdited(pageNo);

        // 어느 초안을 골랐는지는 그 자체로 RLHF 학습 신호 — 페이지 스냅샷과 함께 남긴다
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("element_id", elementId);
        selection.put("from", prevIdx);
        selection.put("to", selectedIdx);
        selection.put("label", selectedIdx < 0 ? null : drafts.get(selectedIdx).get("label"));
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("draft_selected", List.of(selection));
        if (textContents != null) changed.put("text_synced", List.of(elementId));
        saveLog(job, pageResult, userId, jobId, pageNo, mode, isText,
                before, snapshot(live, bboxById), changed);

        log.info("초안 선택: jobId={}, pageNo={}, elementId={}, selectedIdx {} → {}",
                jobId, pageNo, elementId, prevIdx, selectedIdx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", elementId);
        result.put("selectedIdx", selectedIdx);
        result.put("contents", newContents);
        // mode c만 — 텍스트 패널에 반영할 값(짝 텍스트가 없으면 null)
        if ("c".equals(mode)) result.put("textContents", textContents);
        return result;
    }

    /** 초안 1개 → 본문에 넣을 contents. mode a는 점자가 없어 text를 쓰고 마커 형태를 보존한다 */
    private List<String> draftContents(Map<String, Object> draft, List<String> currentContents) {
        Object raw = draft.get("contents");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return draftText(draft, currentContents);
    }

    /** 초안의 묵자(text) — 기존 본문이 점역자주 마커로 감싸여 있었으면 같은 마커로 감싼다 */
    private List<String> draftText(Map<String, Object> draft, List<String> currentContents) {
        String text = draft.get("text") == null ? "" : String.valueOf(draft.get("text"));
        String prev = (currentContents == null || currentContents.isEmpty()) ? "" : currentContents.get(0);
        if (prev.contains(TN_OPEN) && prev.contains(TN_CLOSE)) {
            text = TN_OPEN + text + TN_CLOSE;
        }
        return List.of(text);
    }

    /** 편집 대상 요소 목록 — mode가 테이블을 정한다(a=text, b·c=braille). 삭제분은 리포지토리가 걸러낸다 */
    private List<Element> loadLive(PageResult pageResult, boolean isText) {
        return isText
                ? textElementRepository.findByPageResult(pageResult).stream().map(el -> (Element) new TextView(el)).toList()
                : brailleElementRepository.findByPageResult(pageResult).stream().map(el -> (Element) new BrailleView(el)).toList();
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
        return respond(finalOrder, null);
    }

    // mode c 텍스트 저장은 따라 바뀐 점자도 함께 준다 — 점자 패널을 다시 불러오지 않고 갱신하도록.
    // 짝 점자가 없는 텍스트(AI가 점자를 안 준 요소)는 null
    private List<Map<String, Object>> respond(List<Element> finalOrder, Map<String, List<String>> brailleById) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Element el : finalOrder) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", el.elementId());
            m.put("contents", el.contents());
            if (brailleById != null) m.put("brailleContents", brailleById.get(el.elementId()));
            list.add(m);
        }
        return list;
    }
}
