package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlColumnDefine;
import kr.dogfoot.hwplib.object.bodytext.control.ControlEndnote;
import kr.dogfoot.hwplib.object.bodytext.control.ControlFootnote;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.reader.HWPReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * HWP를 "실제 페이지 단위"로 추출한다.
 *
 * <p>페이지 경계 판정: 한글이 저장 시 계산해 둔 레이아웃 캐시(LineSeg)의
 * {@code lineVerticalPosition}(줄 세로 위치)을 이용한다. 같은 페이지 안에서는 y가 증가하고,
 * 페이지가 바뀌면 y가 페이지 상단으로 리셋되므로 <b>y가 작아지는 지점 = 페이지 경계</b>다.
 *
 * <p><b>다단(멀티 칼럼) 문서</b>는 열이 바뀔 때도 y가 리셋되므로, 단 설정
 * ({@code ControlColumnDefine}의 단 개수 N)을 문서 흐름을 따라 추적해
 * <b>N번째 리셋만 페이지 경계</b>로 센다(나머지는 열 이동). 단 정의가 있는 문단의
 * 첫 줄에서 난 리셋은 새 페이지에서 단 영역이 시작된 것이므로 항상 페이지 경계다.
 * 한계: 열 하나가 완전히 비는 비정형 문서는 카운터가 어긋날 수 있다.
 *
 * <p>{@code LineSegItemTag.isFirstLineAtPage()}는 실제 파일에서 항상 false로 저장되어 사용할 수 없다
 * (검증 시 tag 하위 비트가 전혀 쓰이지 않음을 확인).
 *
 * <p>표(중첩 표 포함) 안의 문단도 함께 추출한다. 최상위 문단만 읽으면 서식 문서에서 내용의
 * 40~96%가 누락된다. 표는 줄글과 구분되도록 {@code [표 시작]}/{@code [표 끝]} 마커로 감싸고
 * <b>행=줄, 칸=탭</b>으로 기록해 행·열 구조를 보존한다.
 *
 * <p>각주는 원문 레이아웃과 같이 <b>그 페이지 끝에</b>, 미주는 <b>문서 끝에</b> 모아 붙인다
 * ({@code [각주]}/{@code [미주]} 마커). 머리말·꼬리말은 페이지마다 반복되는 판면 요소라
 * 본문으로 추출하지 않는다.
 */
@Slf4j
@Component
public class HwpPageExtractor {

    private static final String TABLE_START = "[표 시작]";
    private static final String TABLE_END = "[표 끝]";
    private static final String CELL_SEPARATOR = "\t";
    private static final String FOOTNOTE_MARK = "[각주]";
    private static final String ENDNOTE_MARK = "[미주]";

    /** HWP 바이트를 실제 페이지 단위 텍스트 목록으로 변환. */
    public List<String> extractPages(InputStream inputStream) {
        HWPFile hwp;
        try {
            hwp = HWPReader.fromInputStream(inputStream);
        } catch (Exception e) {
            log.warn("HWP 파싱 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        if (hwp == null) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        // 암호 설정/배포용 문서는 본문 대신 안내문만 들어 있어 점역 대상이 될 수 없다
        if (hwp.getFileHeader().hasPassword()
                || hwp.getFileHeader().isDistribution()
                || hwp.getFileHeader().isEncryptPublicCertification()) {
            throw new CustomException(ErrorCode.JOB_HWP_UNSUPPORTED);
        }

        SplitContext ctx = new SplitContext();

        for (Section section : hwp.getBodyText().getSectionList()) {
            for (int i = 0; i < section.getParagraphCount(); i++) {
                appendParagraph(section.getParagraph(i), ctx);
            }
        }
        List<StringBuilder> pages = ctx.finish();

        List<String> result = new ArrayList<>();
        for (StringBuilder page : pages) {
            String text = page.toString().strip();
            if (!text.isEmpty()) result.add(text);
        }
        // 레이아웃 정보가 전혀 없어 한 페이지도 못 만든 경우를 대비
        if (result.isEmpty()) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }

        log.info("HWP 페이지 추출 완료: {}페이지", result.size());
        return result;
    }

    /** 페이지 분리 진행 상태 — 다단 추적·각주 수집 포함 (호출 스레드 안에서만 사용). */
    private static class SplitContext {
        final List<StringBuilder> pages = new ArrayList<>();
        final List<List<String>> footnotes = new ArrayList<>(); // 페이지별 각주 (인덱스가 pages와 짝)
        final List<String> endnotes = new ArrayList<>();        // 미주는 문서 끝에 모음
        int prevY = -1;
        int columnCount = 1; // 현재 단 개수 (단 정의를 만날 때마다 갱신)
        int columnPos = 0;   // 현재 페이지에서 몇 번째 열인지 (0부터)

        SplitContext() {
            addPage();
        }

        void addPage() {
            pages.add(new StringBuilder());
            footnotes.add(new ArrayList<>());
        }

        List<String> currentFootnotes() {
            return footnotes.get(footnotes.size() - 1);
        }

        /** 각주를 그 페이지 끝에, 미주를 문서 끝에 붙여 마무리. */
        List<StringBuilder> finish() {
            for (int i = 0; i < pages.size(); i++) {
                for (String note : footnotes.get(i)) {
                    pages.get(i).append(FOOTNOTE_MARK).append("\n").append(note).append("\n");
                }
            }
            if (!endnotes.isEmpty()) {
                StringBuilder last = pages.get(pages.size() - 1);
                for (String note : endnotes) {
                    last.append(ENDNOTE_MARK).append("\n").append(note).append("\n");
                }
            }
            return pages;
        }
    }

    /** 문단 하나를 현재 페이지에 붙이며, 줄 단위로 페이지 경계(y 리셋)를 감지한다. */
    private void appendParagraph(Paragraph paragraph, SplitContext ctx) {
        // 단 설정이 이 문단부터 바뀌면 단 개수 갱신 + 열 카운터 리셋
        boolean columnDefined = applyColumnDefine(paragraph, ctx);

        String text = normalString(paragraph);
        List<LineSegItem> segments = paragraph.getLineSeg() == null
                ? null : paragraph.getLineSeg().getLineSegItemList();

        // 레이아웃 정보가 없는 문단은 페이지 판정 없이 현재 페이지에 이어붙임
        if (segments == null || segments.isEmpty()) {
            appendText(ctx.pages, text);
            appendControls(paragraph, ctx);
            return;
        }

        for (int s = 0; s < segments.size(); s++) {
            LineSegItem segment = segments.get(s);
            int y = segment.getLineVerticalPosition();

            // y가 작아짐 = 상단으로 돌아감 = 열 이동 또는 새 페이지
            if (ctx.prevY >= 0 && y < ctx.prevY) {
                boolean pageBreak;
                if (columnDefined && s == 0) {
                    // 단 정의 문단의 첫 줄에서 난 리셋 = 새 페이지에서 단 영역 시작
                    pageBreak = true;
                    ctx.columnPos = 0;
                } else {
                    // N번째 리셋만 페이지 경계, 그 전까지는 열 이동 (1단이면 매번 페이지)
                    ctx.columnPos = (ctx.columnPos + 1) % ctx.columnCount;
                    pageBreak = ctx.columnPos == 0;
                }
                if (pageBreak) {
                    ctx.addPage();
                }
            }
            ctx.prevY = y;

            // 이 줄이 담당하는 텍스트 구간만 현재 페이지에 기록 (문단이 페이지를 걸쳐도 분리됨)
            int start = clamp(segment.getTextStartPosition(), text.length());
            int end = (s + 1 < segments.size())
                    ? clamp(segments.get(s + 1).getTextStartPosition(), text.length())
                    : text.length();
            if (end > start) {
                current(ctx.pages).append(text, start, end);
            }
        }
        current(ctx.pages).append("\n");

        // 표·각주는 이 문단에 앵커링되므로 문단이 끝난 페이지 기준으로 기록
        appendControls(paragraph, ctx);
    }

    /** 문단의 단 정의(ControlColumnDefine)를 반영. 있었으면 true. */
    private boolean applyColumnDefine(Paragraph paragraph, SplitContext ctx) {
        if (paragraph.getControlList() == null) return false;

        boolean found = false;
        for (Control control : paragraph.getControlList()) {
            if (control instanceof ControlColumnDefine columnDefine) {
                ctx.columnCount = Math.max(1,
                        columnDefine.getHeader().getProperty().getColumnCount());
                ctx.columnPos = 0;
                found = true;
            }
        }
        return found;
    }

    /** 문단에 붙은 표·각주·미주를 처리한다. */
    private void appendControls(Paragraph paragraph, SplitContext ctx) {
        if (paragraph.getControlList() == null) return;

        for (Control control : paragraph.getControlList()) {
            if (control instanceof ControlTable table) {
                appendTable(table, ctx.pages);
            } else if (control instanceof ControlFootnote footnote) {
                String note = noteText(footnote.getParagraphList());
                if (!note.isBlank()) ctx.currentFootnotes().add(note);
            } else if (control instanceof ControlEndnote endnote) {
                String note = noteText(endnote.getParagraphList());
                if (!note.isBlank()) ctx.endnotes.add(note);
            }
        }
    }

    /**
     * 표를 마커로 감싸 기록한다 — 행은 줄, 칸은 탭으로 구분해 구조를 보존한다.
     * 중첩 표는 바깥 표 블록이 끝난 뒤 별도 블록으로 이어 붙인다(행 구조를 깨지 않기 위함).
     */
    private void appendTable(ControlTable table, List<StringBuilder> pages) {
        List<ControlTable> nestedTables = new ArrayList<>();
        StringBuilder rows = new StringBuilder();

        for (Row row : table.getRowList()) {
            List<String> cells = new ArrayList<>();
            boolean hasText = false;
            for (Cell cell : row.getCellList()) {
                String cellText = cellText(cell, nestedTables);
                cells.add(cellText);
                if (!cellText.isBlank()) hasText = true;
            }
            // 빈 행(레이아웃용 여백 행)은 표 구조에 의미가 없어 생략
            if (hasText) {
                rows.append(String.join(CELL_SEPARATOR, cells)).append("\n");
            }
        }

        if (rows.length() > 0) {
            current(pages).append(TABLE_START).append("\n")
                    .append(rows)
                    .append(TABLE_END).append("\n");
        }
        for (ControlTable nested : nestedTables) {
            appendTable(nested, pages);
        }
    }

    /** 셀 하나의 텍스트 — 여러 문단은 공백으로 이어 붙여 한 칸이 한 줄을 넘지 않게 한다. */
    private String cellText(Cell cell, List<ControlTable> nestedTables) {
        ParagraphList cellParagraphs = cell.getParagraphList();
        if (cellParagraphs == null) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cellParagraphs.getParagraphCount(); i++) {
            Paragraph cellParagraph = cellParagraphs.getParagraph(i);
            String text = normalString(cellParagraph).strip();
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(text);
            }
            if (cellParagraph.getControlList() != null) {
                for (Control control : cellParagraph.getControlList()) {
                    if (control instanceof ControlTable nested) nestedTables.add(nested);
                }
            }
        }
        return sb.toString();
    }

    /** 각주·미주 본문 — 문단을 줄바꿈으로 이어 붙인다. */
    private String noteText(ParagraphList noteParagraphs) {
        if (noteParagraphs == null) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < noteParagraphs.getParagraphCount(); i++) {
            String text = normalString(noteParagraphs.getParagraph(i)).strip();
            if (!text.isEmpty()) sb.append(text).append("\n");
        }
        return sb.toString().strip();
    }

    private void appendText(List<StringBuilder> pages, String text) {
        if (text.isBlank()) return;
        current(pages).append(text).append("\n");
    }

    private StringBuilder current(List<StringBuilder> pages) {
        return pages.get(pages.size() - 1);
    }

    private int clamp(long position, int textLength) {
        if (position < 0) return 0;
        return (int) Math.min(position, textLength);
    }

    /** 제어문자 등으로 추출이 실패해도 전체 변환을 막지 않도록 빈 문자열로 처리. */
    private String normalString(Paragraph paragraph) {
        try {
            String text = paragraph.getNormalString();
            return text == null ? "" : text;
        } catch (Exception e) {
            return "";
        }
    }
}
