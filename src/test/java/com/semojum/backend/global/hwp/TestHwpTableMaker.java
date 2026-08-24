package com.semojum.backend.global.hwp;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.CtrlHeaderGso;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.HeightCriterion;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.HorzRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.ObjectNumberSort;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.RelativeArrange;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.TextFlowMethod;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.TextHorzArrange;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.VertRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.WidthCriterion;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.DivideAtPageBoundary;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.BorderFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.BorderThickness;
import kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 일회성 도구 — 복잡 표 케이스(가로+세로 병합, 중첩 표) 실측용 HWP 생성 (커밋 제외 대상).
 * 실행: MAKE_TEST_HWP=<출력 경로> ./gradlew test --tests TestHwpTableMaker --rerun
 */
@EnabledIfEnvironmentVariable(named = "MAKE_TEST_HWP", matches = ".+")
class TestHwpTableMaker {

    private HWPFile hwp;
    private int borderFillId;

    @Test
    void make() throws Exception {
        hwp = BlankFileMaker.make();
        borderFillId = createBorderFill();

        Section section = hwp.getBodyText().getSectionList().get(0);
        Paragraph anchor = section.getParagraph(0);

        // 표 A — 4행×3열, 세로 병합(국어 rowSpan=2) + 가로 병합(합계 colSpan=2)
        anchor.getText().addExtendCharForTable();
        ControlTable a = (ControlTable) anchor.addNewControl(ControlType.Table);
        gsoHeader(a.getHeader(), mm(120), mm(40));
        tableRecord(a, 4, 3, new int[]{3, 3, 2, 2});
        // row0: 과목|담당|시수
        Row r0 = a.addNewRow();
        cell(r0, 0, 0, 1, 1, mm(40), mm(10), "과목");
        cell(r0, 0, 1, 1, 1, mm(40), mm(10), "담당");
        cell(r0, 0, 2, 1, 1, mm(40), mm(10), "시수");
        // row1: 국어(세로 2칸)|김OO|4
        Row r1 = a.addNewRow();
        cell(r1, 1, 0, 2, 1, mm(40), mm(20), "국어");
        cell(r1, 1, 1, 1, 1, mm(40), mm(10), "김OO");
        cell(r1, 1, 2, 1, 1, mm(40), mm(10), "4");
        // row2: (국어가 차지)|이OO|3
        Row r2 = a.addNewRow();
        cell(r2, 2, 1, 1, 1, mm(40), mm(10), "이OO");
        cell(r2, 2, 2, 1, 1, mm(40), mm(10), "3");
        // row3: 합계(가로 2칸)|7
        Row r3 = a.addNewRow();
        cell(r3, 3, 0, 1, 2, mm(80), mm(10), "합계");
        cell(r3, 3, 2, 1, 1, mm(40), mm(10), "7");

        // 표 B — 2행×2열, (1,1) 셀 안에 중첩 표 2×2
        Paragraph anchor2 = section.addNewParagraph();
        anchor2.getHeader().setParaShapeId(1);
        anchor2.getHeader().setStyleId((short) 1);
        anchor2.createText();
        anchor2.getText().addExtendCharForTable();
        ControlTable b = (ControlTable) anchor2.addNewControl(ControlType.Table);
        gsoHeader(b.getHeader(), mm(120), mm(40));
        tableRecord(b, 2, 2, new int[]{2, 2});
        Row b0 = b.addNewRow();
        cell(b0, 0, 0, 1, 1, mm(60), mm(10), "바깥 A1");
        cell(b0, 0, 1, 1, 1, mm(60), mm(10), "바깥 B1");
        Row b1 = b.addNewRow();
        cell(b1, 1, 0, 1, 1, mm(60), mm(30), "바깥 A2");
        Cell host = cell(b1, 1, 1, 1, 1, mm(60), mm(30), "안쪽 표:");
        // 중첩 표 — host 셀의 문단에 부착
        Paragraph hostPara = host.getParagraphList().getParagraph(0);
        hostPara.getText().addExtendCharForTable();
        ControlTable nested = (ControlTable) hostPara.addNewControl(ControlType.Table);
        gsoHeader(nested.getHeader(), mm(50), mm(16));
        tableRecord(nested, 2, 2, new int[]{2, 2});
        Row n0 = nested.addNewRow();
        cell(n0, 0, 0, 1, 1, mm(25), mm(8), "n11");
        cell(n0, 0, 1, 1, 1, mm(25), mm(8), "n12");
        Row n1 = nested.addNewRow();
        cell(n1, 1, 0, 1, 1, mm(25), mm(8), "n21");
        cell(n1, 1, 1, 1, 1, mm(25), mm(8), "n22");

        HWPWriter.toFile(hwp, System.getenv("MAKE_TEST_HWP"));
        System.out.println("생성 완료: " + System.getenv("MAKE_TEST_HWP"));
    }

    private long mm(int v) { return v * 283L; }   // 1mm ≈ 283 HWPUNIT

    private void gsoHeader(CtrlHeaderGso h, long w, long hgt) {
        var p = h.getProperty();
        p.setLikeWord(false);
        p.setApplyLineSpace(false);
        p.setVertRelTo(VertRelTo.Para);
        p.setVertRelativeArrange(RelativeArrange.TopOrLeft);
        p.setHorzRelTo(HorzRelTo.Para);
        p.setHorzRelativeArrange(RelativeArrange.TopOrLeft);
        p.setVertRelToParaLimit(false);
        p.setAllowOverlap(false);
        p.setWidthCriterion(WidthCriterion.Absolute);
        p.setHeightCriterion(HeightCriterion.Absolute);
        p.setProtectSize(false);
        p.setTextFlowMethod(TextFlowMethod.TakePlace);
        p.setTextHorzArrange(TextHorzArrange.BothSides);
        p.setObjectNumberSort(ObjectNumberSort.Table);
        h.setxOffset(0);
        h.setyOffset(0);
        h.setWidth(w);
        h.setHeight(hgt);
        h.setzOrder(0);
        h.setOutterMarginLeft(0);
        h.setOutterMarginRight(0);
        h.setOutterMarginTop(0);
        h.setOutterMarginBottom(0);
    }

    private void tableRecord(ControlTable t, int rows, int cols, int[] cellsPerRow) {
        var tb = t.getTable();
        tb.getProperty().setDivideAtPageBoundary(DivideAtPageBoundary.DivideByCell);
        tb.getProperty().setAutoRepeatTitleRow(false);
        tb.setRowCount(rows);
        tb.setColumnCount(cols);
        tb.setCellSpacing(0);
        tb.setLeftInnerMargin(141);
        tb.setRightInnerMargin(141);
        tb.setTopInnerMargin(141);
        tb.setBottomInnerMargin(141);
        for (int c : cellsPerRow) tb.getCellCountOfRowList().add(c);
        tb.setBorderFillId(borderFillId);
    }

    private Cell cell(Row row, int rowIdx, int colIdx, int rowSpan, int colSpan,
                      long w, long h, String text) throws Exception {
        Cell c = row.addNewCell();
        ListHeaderForCell lh = c.getListHeader();
        lh.setRowIndex(rowIdx);
        lh.setColIndex(colIdx);
        lh.setRowSpan(rowSpan);
        lh.setColSpan(colSpan);
        lh.setWidth(w);
        lh.setHeight(h);
        lh.setLeftMargin(141);
        lh.setRightMargin(141);
        lh.setTopMargin(141);
        lh.setBottomMargin(141);
        lh.setBorderFillId(borderFillId);
        lh.setTextWidth(w);
        lh.setFieldName("");
        lh.setParaCount(1);

        Paragraph p = c.getParagraphList().addNewParagraph();
        p.getHeader().setLastInList(true);
        p.getHeader().setParaShapeId(1);
        p.getHeader().setStyleId((short) 1);
        p.createText();
        p.getText().addString(text);
        p.createCharShape();
        p.getCharShape().addParaCharShape(0, 1);
        p.createLineSeg();
        p.getLineSeg().addNewLineSegItem();
        return c;
    }

    // 실선 테두리 BorderFill 등록 → id 반환 (1부터 시작하는 인덱스)
    private int createBorderFill() {
        BorderFill bf = hwp.getDocInfo().addNewBorderFill();
        bf.getProperty().setValue(0);
        bf.getLeftBorder().setType(BorderType.Solid);
        bf.getLeftBorder().setThickness(BorderThickness.MM0_12);
        bf.getLeftBorder().getColor().setValue(0);
        bf.getRightBorder().setType(BorderType.Solid);
        bf.getRightBorder().setThickness(BorderThickness.MM0_12);
        bf.getRightBorder().getColor().setValue(0);
        bf.getTopBorder().setType(BorderType.Solid);
        bf.getTopBorder().setThickness(BorderThickness.MM0_12);
        bf.getTopBorder().getColor().setValue(0);
        bf.getBottomBorder().setType(BorderType.Solid);
        bf.getBottomBorder().setThickness(BorderThickness.MM0_12);
        bf.getBottomBorder().getColor().setValue(0);
        bf.getDiagonalBorder().setType(BorderType.None);
        bf.getDiagonalBorder().setThickness(BorderThickness.MM0_1);
        bf.getDiagonalBorder().getColor().setValue(0);
        return hwp.getDocInfo().getBorderFillList().size();
    }
}
