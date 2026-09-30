package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HwpxToHtmlTest {

    private static final String NS = "xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\" "
            + "xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" "
            + "xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\" "
            + "xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\" "
            + "xmlns:opf=\"http://www.idpf.org/2007/opf/\"";

    private static final String HPF = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><opf:package " + NS + ">"
            + "<opf:manifest>"
            + "<opf:item id=\"section0\" href=\"Contents/section0.xml\" media-type=\"application/xml\"/>"
            + "<opf:item id=\"section1\" href=\"Contents/section1.xml\" media-type=\"application/xml\"/>"
            + "<opf:item id=\"image1\" href=\"BinData/image1.png\" media-type=\"image/png\"/>"
            + "</opf:manifest><opf:spine>"
            + "<opf:itemref idref=\"section0\"/><opf:itemref idref=\"section1\"/>"
            + "</opf:spine></opf:package>";

    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hh:head " + NS + ">"
            + "<hh:charPr id=\"0\" height=\"1000\" textColor=\"#000000\"/>"
            + "<hh:charPr id=\"1\" height=\"2000\" textColor=\"#000000\"><hh:bold/></hh:charPr>"
            + "<hh:paraPr id=\"0\"><hh:align horizontal=\"JUSTIFY\"/></hh:paraPr>"
            + "<hh:paraPr id=\"1\"><hh:align horizontal=\"CENTER\"/></hh:paraPr>"
            + "</hh:head>";

    private static String p(String attrs, String runs) {
        return "<hp:p paraPrIDRef=\"0\" " + attrs + ">" + runs + "</hp:p>";
    }

    private static String run(String charPr, String inner) {
        return "<hp:run charPrIDRef=\"" + charPr + "\">" + inner + "</hp:run>";
    }

    private static String sub(String paragraphs) {
        return "<hp:subList>" + paragraphs + "</hp:subList>";
    }

    private static final String SECTION0 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hs:sec " + NS + ">"
            + "<hp:p paraPrIDRef=\"1\">" + run("0",
                "<hp:secPr><hp:pagePr landscape=\"WIDELY\" width=\"59528\" height=\"84188\">"
                + "<hp:margin left=\"8504\" right=\"8504\" top=\"5668\" bottom=\"4252\"/></hp:pagePr></hp:secPr>"
                + "<hp:ctrl><hp:header>" + sub(p("", run("0", "<hp:t>교육청 머리말</hp:t>"))) + "</hp:header></hp:ctrl>"
                + "<hp:ctrl><hp:footer>" + sub(p("", run("0", "<hp:t>- 3 -</hp:t>"))) + "</hp:footer></hp:ctrl>")
            + run("1", "<hp:t>제목 &amp; 부제</hp:t>") + "</hp:p>"
            + p("", run("0", "<hp:t>앞 문장<hp:tab/>탭<hp:lineBreak/>둘째 줄</hp:t>"
                + "<hp:ctrl><hp:footNote>" + sub(p("", run("0", "<hp:t>각주 내용</hp:t>"))) + "</hp:footNote></hp:ctrl>"))
            + p("", run("0", "<hp:t>표 앞</hp:t>"
                + "<hp:tbl rowCnt=\"2\" colCnt=\"2\"><hp:tr>"
                + "<hp:tc><hp:subList>" + p("", run("0", "<hp:t>병합 셀</hp:t>")) + "</hp:subList>"
                + "<hp:cellSpan colSpan=\"2\" rowSpan=\"1\"/><hp:cellSz width=\"7200\" height=\"1000\"/></hp:tc>"
                + "</hp:tr><hp:tr>"
                + "<hp:tc><hp:subList>" + p("", run("0", "<hp:t>A</hp:t>")) + "</hp:subList><hp:cellSpan colSpan=\"1\" rowSpan=\"1\"/></hp:tc>"
                + "<hp:tc><hp:subList>" + p("", run("0", "<hp:t>B</hp:t>")) + "</hp:subList><hp:cellSpan colSpan=\"1\" rowSpan=\"1\"/></hp:tc>"
                + "</hp:tr></hp:tbl><hp:t>표 뒤</hp:t>"))
            + p("", run("0", "<hp:pic><hp:curSz width=\"14400\" height=\"7200\"/><hc:img binaryItemIDRef=\"image1\"/></hp:pic>"
                + "<hp:rect><hp:drawText>" + sub(p("", run("0", "<hp:t>글상자 글</hp:t>"))) + "</hp:drawText></hp:rect>"
                + "<hp:equation><hp:script>x over 2</hp:script></hp:equation>"))
            + "</hs:sec>";

    private static final String SECTION1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hs:sec " + NS + ">"
            + p("pageBreak=\"1\"", run("0", "<hp:t>둘째 구역 &lt;꺾쇠&gt;</hp:t>"))
            + "</hs:sec>";

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> sample() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("mimetype", HwpxToHtml.MIMETYPE.getBytes(StandardCharsets.US_ASCII));
        m.put("Contents/content.hpf", HPF.getBytes(StandardCharsets.UTF_8));
        m.put("Contents/header.xml", HEADER.getBytes(StandardCharsets.UTF_8));
        // spine 순서가 이름 순서와 같아도, 뒤 구역을 먼저 넣어 ZIP 순서에 기대지 않는지 본다
        m.put("Contents/section1.xml", SECTION1.getBytes(StandardCharsets.UTF_8));
        m.put("Contents/section0.xml", SECTION0.getBytes(StandardCharsets.UTF_8));
        m.put("BinData/image1.png", new byte[]{1, 2, 3});
        return m;
    }

    @Test
    void ZIP_시그니처로_HWPX를_가린다() throws Exception {
        assertTrue(HwpxToHtml.isZip(zip(sample())));
        assertFalse(HwpxToHtml.isZip(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0})); // HWP 5.x(OLE)
        assertFalse(HwpxToHtml.isZip(new byte[]{'P', 'K'}));
    }

    @Test
    void 본문_표_그림_각주_글상자_수식을_빠짐없이_옮긴다() throws Exception {
        HwpxToHtml.Result r = HwpxToHtml.convert(zip(sample()));
        String html = r.html();

        // 문단 서식: 가운데 정렬 + 20pt 굵게, 특수문자 이스케이프
        assertTrue(html.contains("text-align:center;"), html);
        assertTrue(html.contains("<span style=\"font-size:20.0pt;font-weight:bold;\">제목 &amp; 부제</span>"), html);
        // 탭·줄바꿈
        assertTrue(html.contains("앞 문장&#160;&#160;&#160;&#160;탭<br/>둘째 줄"), html);
        // 표: 앞 문단을 끊고 표를 내보낸 뒤 이어간다 (p 안에 table 금지)
        int before = html.indexOf("표 앞</p>");
        int table = html.indexOf("<table border=\"1\"");
        int after = html.indexOf("표 뒤");
        assertTrue(before >= 0 && before < table && table < after, html);
        assertTrue(html.contains("<td colspan=\"2\" style=\"width:25.4mm\">"), html);
        assertTrue(html.contains(">A</p></td>") && html.contains(">B</p></td>"), html);
        // 그림은 파일로 빼고 상대 경로로 참조
        assertTrue(html.contains("<img src=\"img0.png\" style=\"width:50.8mm\"/>"), html);
        assertArrayEquals(new byte[]{1, 2, 3}, r.images().get("img0.png"));
        // 글상자·수식 스크립트
        assertTrue(html.contains("글상자 글"), html);
        assertTrue(html.contains("x over 2"), html);
        // 각주: 본문엔 번호, 내용은 끝으로
        assertTrue(html.contains("둘째 줄<sup>1)</sup>"), html);
        assertTrue(html.indexOf("각주 내용") > html.indexOf("둘째 구역"), html);
    }

    @Test
    void 구역은_spine_순서로_잇고_쪽_나눔을_지킨다() throws Exception {
        String html = HwpxToHtml.convert(zip(sample())).html();
        assertTrue(html.indexOf("제목") < html.indexOf("둘째 구역"), html);
        assertTrue(html.contains("<p style=\"page-break-before:always;text-align:justify;\">"
                + "둘째 구역 &lt;꺾쇠&gt;</p>"), html);
    }

    @Test
    void 용지_크기와_여백은_첫_구역_설정을_따른다() throws Exception {
        String html = HwpxToHtml.convert(zip(sample())).html();
        assertTrue(html.contains("@page{size:210.0mm 297.0mm;margin:20.0mm 30.0mm 15.0mm 30.0mm;}"), html);
    }

    @Test
    void 머리말_꼬리말은_HWP와_같은_마커로_본문_앞뒤에_넣는다() throws Exception {
        String html = HwpxToHtml.convert(zip(sample())).html();
        int header = html.indexOf("[머리말] 교육청 머리말");
        int footer = html.indexOf("[꼬리말] - 3 -");
        assertTrue(header >= 0 && header < html.indexOf("제목"), html);
        assertTrue(footer > html.indexOf("둘째 구역"), html);
        // 머리말 본문이 본문 문단으로 새지 않는다
        assertEquals(1, html.split("교육청 머리말", -1).length - 1, html);
    }

    @Test
    void HWPX가_아닌_ZIP은_JOB4007() throws Exception {
        Map<String, byte[]> docx = new LinkedHashMap<>();
        docx.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        CustomException e = assertThrows(CustomException.class, () -> HwpxToHtml.convert(zip(docx)));
        assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
    }

    @Test
    void 암호_HWPX는_미지원으로_거절한다() throws Exception {
        Map<String, byte[]> m = sample();
        m.put("META-INF/manifest.xml",
                "<manifest><file-entry><encryption-data/></file-entry></manifest>".getBytes(StandardCharsets.UTF_8));
        CustomException e = assertThrows(CustomException.class, () -> HwpxToHtml.convert(zip(m)));
        assertEquals(ErrorCode.JOB_HWP_UNSUPPORTED, e.getErrorCode());
    }

    @Test
    void DOCTYPE이_있는_XML은_거절한다_XXE() throws Exception {
        Map<String, byte[]> m = sample();
        m.put("Contents/section0.xml", ("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<hs:sec " + NS + ">" + p("", run("0", "<hp:t>&e;</hp:t>")) + "</hs:sec>").getBytes(StandardCharsets.UTF_8));
        CustomException e = assertThrows(CustomException.class, () -> HwpxToHtml.convert(zip(m)));
        assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
    }
}
