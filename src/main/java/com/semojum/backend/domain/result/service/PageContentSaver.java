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
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

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

    // ── 패널별 diff ──────────────────────────────────────────────────
    // 배열 순서가 곧 읽기 순서. 배열에서 빠진 기존 블록은 삭제, id가 null인 항목은 새 블록이다.

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
                if (el == null) throw new CustomException(ErrorCode.ELEMENT_NOT_FOUND);
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
                if (el == null) throw new CustomException(ErrorCode.ELEMENT_NOT_FOUND);
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

        pageMemoRepository.deleteByPageId(pageId);
        for (JobRequestDto.SaveMemo m : memos) {
            String panel = m.panel() == null ? PageMemo.PANEL_TEXT : m.panel().toUpperCase();
            if (!PageMemo.PANEL_TEXT.equals(panel) && !PageMemo.PANEL_BRAILLE.equals(panel)) {
                throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            }
            pageMemoRepository.save(PageMemo.builder()
                    .pageId(pageId)
                    .panel(panel)
                    .elementId(m.elementId())
                    .lineIndex(m.lineIndex())
                    .lineText(m.lineText())
                    .body(m.body())
                    .createdAt(m.createdAt())
                    .build());
        }
        return true;
    }
}
