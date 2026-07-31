package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
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
 * <p>{@code LineSegItemTag.isFirstLineAtPage()}는 실제 파일에서 항상 false로 저장되어 사용할 수 없다
 * (검증 시 tag 하위 비트가 전혀 쓰이지 않음을 확인).
 *
 * <p>표(중첩 표 포함) 안의 문단도 함께 추출한다. 최상위 문단만 읽으면 서식 문서에서 내용의
 * 40~96%가 누락된다.
 */
@Slf4j
@Component
public class HwpPageExtractor {

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

        List<StringBuilder> pages = new ArrayList<>();
        pages.add(new StringBuilder());
        int prevY = -1;

        for (Section section : hwp.getBodyText().getSectionList()) {
            for (int i = 0; i < section.getParagraphCount(); i++) {
                prevY = appendParagraph(section.getParagraph(i), pages, prevY);
            }
        }

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

    /** 문단 하나를 현재 페이지에 붙이며, 줄 단위로 페이지 경계(y 리셋)를 감지한다. */
    private int appendParagraph(Paragraph paragraph, List<StringBuilder> pages, int prevY) {
        String text = normalString(paragraph);
        List<LineSegItem> segments = paragraph.getLineSeg() == null
                ? null : paragraph.getLineSeg().getLineSegItemList();

        // 레이아웃 정보가 없는 문단은 페이지 판정 없이 현재 페이지에 이어붙임
        if (segments == null || segments.isEmpty()) {
            appendText(pages, text);
            appendTables(paragraph, pages);
            return prevY;
        }

        for (int s = 0; s < segments.size(); s++) {
            LineSegItem segment = segments.get(s);
            int y = segment.getLineVerticalPosition();

            // y가 작아짐 = 페이지 상단으로 돌아감 = 새 페이지 시작
            if (prevY >= 0 && y < prevY) {
                pages.add(new StringBuilder());
            }
            prevY = y;

            // 이 줄이 담당하는 텍스트 구간만 현재 페이지에 기록 (문단이 페이지를 걸쳐도 분리됨)
            int start = clamp(segment.getTextStartPosition(), text.length());
            int end = (s + 1 < segments.size())
                    ? clamp(segments.get(s + 1).getTextStartPosition(), text.length())
                    : text.length();
            if (end > start) {
                current(pages).append(text, start, end);
            }
        }
        current(pages).append("\n");

        // 표는 이 문단에 앵커링되므로 문단이 끝난 페이지에 함께 기록
        appendTables(paragraph, pages);
        return prevY;
    }

    /** 문단에 붙은 표의 셀 문단을 재귀적으로(중첩 표 포함) 추출. */
    private void appendTables(Paragraph paragraph, List<StringBuilder> pages) {
        if (paragraph.getControlList() == null) return;

        for (Control control : paragraph.getControlList()) {
            if (!(control instanceof ControlTable table)) continue;

            for (Row row : table.getRowList()) {
                for (Cell cell : row.getCellList()) {
                    ParagraphList cellParagraphs = cell.getParagraphList();
                    if (cellParagraphs == null) continue;

                    for (int i = 0; i < cellParagraphs.getParagraphCount(); i++) {
                        Paragraph cellParagraph = cellParagraphs.getParagraph(i);
                        appendText(pages, normalString(cellParagraph));
                        appendTables(cellParagraph, pages);
                    }
                }
            }
        }
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
