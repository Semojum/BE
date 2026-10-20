package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 작업 저장의 <b>쪽 하나</b>를 책임진다 — 세 패널 중 텍스트·점자와 줄 메모.
 *
 * <p><b>왜 별도 빈인가</b>: 쪽마다 독립 트랜잭션이어야 한 쪽이 실패해도 나머지가 저장된다.
 * 205쪽을 한 트랜잭션으로 묶으면 한 쪽 때문에 하루치 편집이 통째로 날아간다.
 * 같은 클래스 안에서 호출하면 프록시를 타지 않아 {@code REQUIRES_NEW}가 걸리지 않으므로 분리했다.
 *
 * <p><b>AI를 부르지 않는다.</b> FE 점역 엔진이 텍스트·점자를 모두 만들어 보내므로 서버는
 * 받은 대로 쓴다 — 구 저장 경로(mode c 텍스트 저장 시 재점역)와 다른 점이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageContentSaver {

    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final PageMemoRepository pageMemoRepository;
    private final com.semojum.backend.domain.result.repository.PageMemoReplyRepository memoReplyRepository;

    /**
     * 한 쪽을 저장한다. 예외는 호출자가 받아 그 쪽만 실패로 기록한다.
     *
     * @return 내용이 실제로 바뀌었으면 true (카드 날짜 갱신 여부 판단용)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean savePage(Job job, JobRequestDto.SaveJobPage page) {
        int pageNo = page.pageNo();
        PageResult pageResult = pageResultRepository
                .findByJobIdAndPageNumber(job.getId(), pageNo)
                .orElseGet(() -> createTextPage(job, pageNo));

        boolean changed = false;
        if (page.text() != null) changed |= saveText(pageResult, page.text());
        if (page.braille() != null) changed |= saveBraille(pageResult, page.braille());

        // 메모는 그 쪽 것을 통째로 갈아끼운다 — 본문이 최종 상태이므로 diff를 따로 두지 않는다
        if (page.memos() != null) {
            Page p = pageRepository.findByJobAndPageNo(job, pageNo)
                    .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
            changed |= replaceMemos(p.getId(), page.memos());
        }

        if (changed) job.markContentEdited(pageNo);
        return changed;
    }

    /** 텍스트 작업의 쪽 결과 상태 — AI 결과가 아니라 저장으로 생긴 행이다 */
    public static final String STATUS_SAVED = "SAVED";

    /**
     * 텍스트 작업(TXT · 새 문서)의 쪽을 <b>앱이 보낸 쪽 번호대로</b> 처음 만든다 (V3.1).
     *
     * <p>텍스트 작업은 AI를 거치지 않아 생성 때 쪽이 하나도 없다 — 쪽 나누기는 앱이 정한다.
     * 그래서 첫 저장이 쪽과 빈 쪽 결과를 만들고, 그 위에 받은 블록을 붙인다.
     *
     * <p>PDF·HWP·이미지 작업은 쪽이 AI 변환으로 만들어지므로 <b>여기서 만들지 않는다</b> —
     * 없는 쪽 번호가 오면 종전대로 JOB4001이다(앱 버그를 그대로 드러낸다).
     *
     * <p>쪽 결과 상태를 {@link #STATUS_SAVED}로 두는 이유: 운영 통계가 쪽 결과의
     * COMPLETED·NEEDS_REVIEW를 <b>변환 쪽수</b>로 센다. AI가 돌지 않은 쪽을 거기 넣으면
     * 변환량·쪽당 원가가 조용히 틀어진다. 쪽({@code pages}) 상태는 COMPLETED라 화면은 정상이다.
     *
     * <p>{@code pdf_path}는 빈 문자열이다 — 칼럼이 NOT NULL인데 원본 파일이 없다.
     * 쪽 조회는 빈 경로면 원본을 내려주지 않는다({@code UserService.buildOriginal}).
     */
    private PageResult createTextPage(Job job, int pageNo) {
        if (!"b".equals(job.getMode())) throw new CustomException(ErrorCode.JOB_NOT_FOUND);
        if (pageNo < 1) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);

        Page p = pageRepository.findByJobAndPageNo(job, pageNo).orElseGet(() -> {
            Page created = Page.builder().job(job).pageNo(pageNo).pdfPath("").build();
            created.updateStatus("COMPLETED");
            return pageRepository.save(created);
        });
        log.info("텍스트 작업 쪽 생성: jobId={}, pageNo={}", job.getId(), pageNo);
        return pageResultRepository.save(PageResult.builder()
                .job(job).page(p).pageNumber(pageNo).mode("b")
                .status(STATUS_SAVED).rawResponse("{}")
                .build());
    }

    // ── 패널별 diff ──────────────────────────────────────────────────
    // 배열 순서가 곧 읽기 순서. 배열에서 빠진 기존 블록은 삭제, id가 null인 항목은 새 블록이다.
    //
    // 블록 id는 앱이 만든다(2026-10-21, FE 점검 B-01). 텍스트 · 점자 두 패널은 같은 id로 짝을 이루는데,
    // 서버가 패널마다 id를 발급하면 짝이 깨지고, V3.1 작업은 점자 블록이 없어(AI가 점자를 안 준다)
    // 첫 점자 저장이 전부 「없는 id」로 실패했다. 그래서:
    //   · 그 쪽에 없는 id → 그 id 그대로 새 블록(첫 점자 · 두 패널 공통 새 블록)
    //   · 그 쪽에서 지운 블록의 id → 삭제 표시를 풀어 복원(되돌리기)
    //   · 같은 id를 다시 보내면 이미 있으니 갱신 — 응답 유실 뒤 재전송해도 중복이 생기지 않는다
    // 찾는 범위가 그 쪽 결과라 다른 작업 · 다른 쪽 블록은 건드리지 못한다. id: null은 종전대로 서버가 발급한다

    /** 앱이 만든 블록 id 형식 — UUID 등 영문 · 숫자 · 하이픈 · 밑줄, 64자 이하 */
    private static final java.util.regex.Pattern CLIENT_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static void validateClientId(String id) {
        if (!CLIENT_ID.matcher(id).matches()) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
    }

    private boolean saveText(PageResult pageResult, List<JobRequestDto.SaveElement> requested) {
        List<TextElement> live = textElementRepository.findByPageResult(pageResult);
        Map<String, TextElement> byId = new HashMap<>();
        for (TextElement el : live) byId.put(el.getElementId(), el);
        Set<String> seen = checkIds(requested);

        boolean changed = false;
        List<TextElement> finalOrder = new ArrayList<>();
        for (JobRequestDto.SaveElement item : requested) {
            if (item.id() == null) {
                TextElement neo = TextElement.builder()
                        .pageResult(pageResult).elementId(UUID.randomUUID().toString())
                        .type("text").contents(item.contents()).isBlocked(false).build();
                neo.markUserAuthored();
                finalOrder.add(textElementRepository.save(neo));
                changed = true;
            } else {
                TextElement el = byId.get(item.id());
                if (el == null) {
                    validateClientId(item.id());
                    el = textElementRepository.findByPageResultAndElementId(pageResult, item.id()).orElse(null);
                    if (el != null) {
                        el.restore();                      // 지웠던 블록 — 되돌리기
                    } else {
                        el = TextElement.builder()          // 앱이 만든 새 블록
                                .pageResult(pageResult).elementId(item.id())
                                .type("text").contents(item.contents()).isBlocked(false).build();
                        el.markUserAuthored();
                        el = textElementRepository.save(el);
                    }
                    changed = true;
                }
                if (!item.contents().equals(el.getCurrentContents())) {
                    el.updateCurrentContents(item.contents());
                    changed = true;
                }
                if (item.selectedIdx() != null && !item.selectedIdx().equals(el.getSelectedIdx())) {
                    el.updateSelectedIdx(item.selectedIdx());   // 본문은 FE가 채워 보낸다 — 선택만 기록
                    changed = true;
                }
                finalOrder.add(el);
            }
        }
        for (TextElement el : live) {
            if (!seen.contains(el.getElementId())) { el.markDeleted(); changed = true; }
        }
        changed |= renumberText(live, finalOrder, seen);
        return changed;
    }

    private boolean saveBraille(PageResult pageResult, List<JobRequestDto.SaveElement> requested) {
        List<BrailleElement> live = brailleElementRepository.findByPageResult(pageResult);
        Map<String, BrailleElement> byId = new HashMap<>();
        for (BrailleElement el : live) byId.put(el.getElementId(), el);
        Set<String> seen = checkIds(requested);

        boolean changed = false;
        List<BrailleElement> finalOrder = new ArrayList<>();
        for (JobRequestDto.SaveElement item : requested) {
            if (item.id() == null) {
                BrailleElement neo = BrailleElement.builder()
                        .pageResult(pageResult).elementId(UUID.randomUUID().toString())
                        .type("text").content(item.contents()).isBlocked(false).build();
                neo.markUserAuthored();
                finalOrder.add(brailleElementRepository.save(neo));
                changed = true;
            } else {
                BrailleElement el = byId.get(item.id());
                if (el == null) {
                    validateClientId(item.id());
                    el = brailleElementRepository.findByPageResultAndElementId(pageResult, item.id()).orElse(null);
                    if (el != null) {
                        el.restore();                      // 지웠던 블록 — 되돌리기
                    } else {
                        // 첫 점자 또는 앱이 만든 새 블록. 짝 텍스트 블록이 있으면 유형 · 제목 단계를 따른다
                        TextElement pair = textElementRepository.findByPageResultAndElementId(pageResult, item.id())
                                .orElse(null);
                        el = BrailleElement.builder()
                                .pageResult(pageResult).elementId(item.id())
                                .type(pair == null ? "text" : pair.getType())
                                .headingLevel(pair == null ? null : pair.getHeadingLevel())
                                .content(item.contents()).isBlocked(false).build();
                        el.markUserAuthored();             // 점자는 AI 원본이 없다(V3.1) — 앱 엔진이 만든 값
                        el = brailleElementRepository.save(el);
                    }
                    changed = true;
                }
                if (!item.contents().equals(el.getCurrentContent())) {
                    el.updateCurrentContent(item.contents());
                    changed = true;
                }
                if (item.selectedIdx() != null && !item.selectedIdx().equals(el.getSelectedIdx())) {
                    el.updateSelectedIdx(item.selectedIdx());   // 본문은 FE가 채워 보낸다 — 선택만 기록
                    changed = true;
                }
                finalOrder.add(el);
            }
        }
        for (BrailleElement el : live) {
            if (!seen.contains(el.getElementId())) { el.markDeleted(); changed = true; }
        }
        changed |= renumberBraille(live, finalOrder, seen);
        return changed;
    }

    /** 중복 id는 화면 상태가 깨진 것 — 저장하면 블록이 합쳐지므로 거절한다 */
    private Set<String> checkIds(List<JobRequestDto.SaveElement> requested) {
        Set<String> seen = new HashSet<>();
        for (JobRequestDto.SaveElement item : requested) {
            if (item.id() != null && !seen.add(item.id())) {
                throw new CustomException(ErrorCode.ELEMENT_LIST_MISMATCH);
            }
        }
        return seen;
    }

    // 살아남은 기존 블록의 상대 순서가 바뀌었으면 순서 변경이다.
    // 순서는 서버가 소유하므로 최종 배열 기준으로 1..N 재번호한다.
    private boolean renumberText(List<TextElement> live, List<TextElement> finalOrder, Set<String> seen) {
        List<String> before = live.stream().map(TextElement::getElementId).filter(seen::contains).toList();
        List<String> after = finalOrder.stream().map(TextElement::getElementId).filter(seen::contains).toList();
        for (int i = 0; i < finalOrder.size(); i++) finalOrder.get(i).updateReadingOrder(i + 1);
        return !before.equals(after);
    }

    private boolean renumberBraille(List<BrailleElement> live, List<BrailleElement> finalOrder, Set<String> seen) {
        List<String> before = live.stream().map(BrailleElement::getElementId).filter(seen::contains).toList();
        List<String> after = finalOrder.stream().map(BrailleElement::getElementId).filter(seen::contains).toList();
        for (int i = 0; i < finalOrder.size(); i++) finalOrder.get(i).updateReadingOrder(i + 1);
        return !before.equals(after);
    }

    // ── 줄 메모 ─────────────────────────────────────────────────────

    private boolean replaceMemos(UUID pageId, List<JobRequestDto.SaveMemo> memos) {
        List<PageMemo> existing = pageMemoRepository.findByPageIdOrderByElementIdAscLineIndexAsc(pageId);
        if (existing.isEmpty() && memos.isEmpty()) return false;

        // 답글을 먼저 지운다 — 메모를 지우면 답글이 가리킬 곳이 사라진다(FK는 두지 않았다)
        if (!existing.isEmpty()) {
            memoReplyRepository.deleteByMemoIdIn(existing.stream().map(PageMemo::getId).toList());
        }
        pageMemoRepository.deleteByPageId(pageId);
        for (JobRequestDto.SaveMemo m : memos) {
            String panel = m.panel() == null ? PageMemo.PANEL_TEXT : m.panel().toUpperCase();
            if (!PageMemo.PANEL_TEXT.equals(panel) && !PageMemo.PANEL_BRAILLE.equals(panel)) {
                throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            }
            PageMemo saved = pageMemoRepository.save(PageMemo.builder()
                    .pageId(pageId)
                    .panel(panel)
                    .elementId(m.elementId())
                    .lineIndex(m.lineIndex())
                    .lineText(m.lineText())
                    .body(m.body())
                    .createdAt(m.createdAt())
                    .author(validAuthor(m.author()))
                    .resolved(Boolean.TRUE.equals(m.resolved()))
                    .resolvedAt(m.resolvedAt())
                    .build());
            if (m.replies() == null) continue;
            for (JobRequestDto.SaveMemoReply r : m.replies()) {
                if (r == null || r.body() == null || r.body().isBlank()) {
                    throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
                }
                memoReplyRepository.save(PageMemoReply.builder()
                        .memoId(saved.getId())
                        .author(validAuthor(r.author()))
                        .body(r.body())
                        .createdAt(r.createdAt())
                        .build());
            }
        }
        return true;
    }

    /** 작성자 이름 — 앱이 보낸 값 그대로. 빈 값은 null, 상한(100자)을 넘으면 400 */
    private static String validAuthor(String author) {
        if (author == null || author.isBlank()) return null;
        String a = author.trim();
        if (a.length() > PageMemo.AUTHOR_MAX) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        return a;
    }
}
