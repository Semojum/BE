package com.semojum.backend.global.hwp;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// 일회성 프로브 — HWP_DEBUG_FILE의 표를 셀 좌표·병합 값 그대로 덤프한다 (커밋 제외 대상)
@EnabledIfEnvironmentVariable(named = "HWP_DEBUG_FILE", matches = ".+")
class TableProbeTest {

    @Test
    void dumpTables() throws Exception {
        HWPFile file = HWPReader.fromFile(System.getenv("HWP_DEBUG_FILE"));
        int tableNo = 0;
        for (Section section : file.getBodyText().getSectionList()) {
            for (int i = 0; i < section.getParagraphCount(); i++) {
                Paragraph p = section.getParagraph(i);
                if (p.getControlList() == null) continue;
                for (Control c : p.getControlList()) {
                    if (c instanceof ControlTable table) dump(table, ++tableNo);
                }
            }
        }
    }

    private void dump(ControlTable table, Object no) throws Exception {
        System.out.println("=== 표 " + no + " (논리 격자 " + table.getTable().getRowCount()
                + "×" + table.getTable().getColumnCount() + ", 저장 행 " + table.getRowList().size() + ") ===");
        int nestedNo = 0;
        for (Row row : table.getRowList()) {
            for (Cell cell : row.getCellList()) {
                var h = cell.getListHeader();
                String text = extract(cell);
                System.out.printf("  row=%d col=%d rowSpan=%d colSpan=%d | %s%n",
                        h.getRowIndex(), h.getColIndex(), h.getRowSpan(), h.getColSpan(),
                        text.replace("\n", " ⏎ ").strip());
                // 셀 안의 중첩 표 재귀 — 어느 셀 안인지 좌표로 표기
                for (int i = 0; i < cell.getParagraphList().getParagraphCount(); i++) {
                    var p = cell.getParagraphList().getParagraph(i);
                    if (p.getControlList() == null) continue;
                    for (Control c : p.getControlList()) {
                        if (c instanceof ControlTable nested) {
                            dump(nested, no + "-중첩" + (++nestedNo)
                                    + " (호스트 셀 row=" + h.getRowIndex() + " col=" + h.getColIndex() + ")");
                        }
                    }
                }
            }
        }
    }

    private String extract(Cell cell) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cell.getParagraphList().getParagraphCount(); i++) {
            try {
                String t = cell.getParagraphList().getParagraph(i).getNormalString();
                if (t != null && !t.isBlank()) sb.append(t).append("\n");
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }
}
