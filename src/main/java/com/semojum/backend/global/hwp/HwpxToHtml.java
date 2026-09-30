package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HWPX(한글 2014+ 기본 저장 형식, OWPML = ZIP + XML) → HTML (2026-09-30).
 *
 * <p>LibreOffice는 HWPX를 열지 못하고(26.2 실측: "source file could not be loaded") pyhwp는 HWP 5.x 전용이라,
 * 본문 XML을 직접 풀어 HTML로 만든 뒤 LibreOffice로 PDF를 뽑는다. 이후는 HWP와 같은 PDF 파이프라인이다.
 *
 * <p>재현 범위는 HWP 경로와 같은 수준 — "읽을 내용이 빠지지 않는 것"이 목표이고 조판은 근사치다.
 * 문단(정렬·글자 크기·굵게·기울임·밑줄)·표(병합)·그림·각주·글상자·수식 스크립트·쪽 나눔을 옮긴다.
 * 머리말·꼬리말은 HWP 경로와 같은 [머리말]/[꼬리말] 마커로 본문 시작/끝에 넣는다.
 * 쪽 크기는 첫 구역의 용지 설정을 따른다.
 */
final class HwpxToHtml {

    static final String MIMETYPE = "application/hwp+zip";

    // ZIP 폭탄 방어 — 정상 문서(그림 포함)는 수십 MB를 넘지 않는다
    private static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024;
    private static final int MAX_ENTRIES = 5000;

    // HWPUNIT = 1/7200 inch
    private static final double HWPUNIT_PER_MM = 7200 / 25.4;

    private static final Pattern SECTION_FILE = Pattern.compile("Contents/section(\\d+)\\.xml");

    /** 변환 결과 — HTML 본문 + HTML이 상대 경로로 참조하는 그림 파일들 */
    record Result(String html, Map<String, byte[]> images) { }

    private final Map<String, byte[]> entries;
    private final Map<String, CharStyle> charStyles = new HashMap<>();
    private final Map<String, String> paraAligns = new HashMap<>();
    private final Map<String, String> binItems = new HashMap<>();       // manifest id → href
    private final Map<String, byte[]> usedImages = new LinkedHashMap<>();
    private final LinkedHashSet<String> headers = new LinkedHashSet<>();
    private final LinkedHashSet<String> footers = new LinkedHashSet<>();
    private final List<String> footnotes = new ArrayList<>();
    private boolean firstParagraph = true;

    private record CharStyle(int heightHundredthPt, boolean bold, boolean italic, boolean underline, String color) { }

    private HwpxToHtml(Map<String, byte[]> entries) {
        this.entries = entries;
    }

    /** ZIP 시그니처(PK\3\4) — .hwp 확장자로 올라온 HWPX를 가려내는 데 쓴다 */
    static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    static Result convert(byte[] hwpxBytes) {
        Map<String, byte[]> entries = unzip(hwpxBytes);
        byte[] mimetype = entries.get("mimetype");
        if (mimetype == null || !MIMETYPE.equals(new String(mimetype, StandardCharsets.US_ASCII).trim())) {
            // ZIP이지만 HWPX가 아님(docx 등을 .hwp로 바꿔 올린 경우)
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        byte[] manifest = entries.get("META-INF/manifest.xml");
        if (manifest != null && new String(manifest, StandardCharsets.UTF_8).contains("encryption-data")) {
            throw new CustomException(ErrorCode.JOB_HWP_UNSUPPORTED); // 암호 문서
        }
        try {
            return new HwpxToHtml(entries).build();
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
    }

    private static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entries.size() >= MAX_ENTRIES) throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
                if (entry.isDirectory()) continue;
                byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_TOTAL_BYTES - total + 1));
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
                entries.put(entry.getName(), data);
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        if (entries.isEmpty()) throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        return entries;
    }

    private Result build() throws Exception {
        List<String> sectionFiles = sectionFiles();
        if (sectionFiles.isEmpty()) throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);

        byte[] header = entries.get("Contents/header.xml");
        if (header != null) readStyles(parse(header));

        StringBuilder body = new StringBuilder();
        String pageCss = null;
        for (String file : sectionFiles) {
            Element sec = parse(entries.get(file)).getDocumentElement();
            if (pageCss == null) pageCss = pageCss(sec);
            renderBlocks(sec, body);
        }
        if (!footnotes.isEmpty()) {
            body.append("<hr/>");
            for (int i = 0; i < footnotes.size(); i++) {
                body.append("<p><sup>").append(i + 1).append(")</sup> ").append(footnotes.get(i)).append("</p>");
            }
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>")
                .append(pageCss == null ? "" : pageCss)
                .append("body{font-family:'NanumMyeongjo','Noto Serif CJK KR',serif;font-size:10pt;}")
                .append("p{margin:0;}")
                .append("table{border-collapse:collapse;margin:2pt 0;}")
                .append("td{border:1px solid #000;padding:1pt 3pt;vertical-align:middle;}")
                .append("</style></head><body>");
        for (String h : headers) html.append("<p>").append(escape(HwpToPdfConverter.HEADER_MARK + " " + h)).append("</p>");
        html.append(body);
        for (String f : footers) html.append("<p>").append(escape(HwpToPdfConverter.FOOTER_MARK + " " + f)).append("</p>");
        html.append("</body></html>");
        return new Result(html.toString(), usedImages);
    }

    // ── 구조 파악 ──────────────────────────────────────────────

    /** content.hpf의 spine 순서를 따르고, 없으면 section 번호순 */
    private List<String> sectionFiles() throws Exception {
        List<String> files = new ArrayList<>();
        byte[] hpf = entries.get("Contents/content.hpf");
        if (hpf != null) {
            Document doc = parse(hpf);
            Map<String, String> hrefs = new HashMap<>();
            for (Element item : descendants(doc.getDocumentElement(), "item")) {
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                hrefs.put(id, href);
                if (href.startsWith("BinData/")) binItems.put(id, href);
            }
            for (Element ref : descendants(doc.getDocumentElement(), "itemref")) {
                String href = hrefs.get(ref.getAttribute("idref"));
                if (href != null && SECTION_FILE.matcher(href).matches() && entries.containsKey(href)) files.add(href);
            }
        }
        if (files.isEmpty()) {
            List<int[]> found = new ArrayList<>();
            for (String name : entries.keySet()) {
                Matcher m = SECTION_FILE.matcher(name);
                if (m.matches()) found.add(new int[]{Integer.parseInt(m.group(1))});
            }
            found.sort((a, b) -> Integer.compare(a[0], b[0]));
            for (int[] n : found) files.add("Contents/section" + n[0] + ".xml");
        }
        return files;
    }

    private void readStyles(Document header) {
        for (Element cp : descendants(header.getDocumentElement(), "charPr")) {
            int height = parseInt(cp.getAttribute("height"), 1000);
            Element u = first(cp, "underline");
            boolean underline = u != null && !"NONE".equals(u.getAttribute("type"));
            String color = cp.getAttribute("textColor");
            charStyles.put(cp.getAttribute("id"), new CharStyle(height,
                    first(cp, "bold") != null, first(cp, "italic") != null, underline,
                    color.matches("#[0-9A-Fa-f]{6}") ? color : null));
        }
        for (Element pp : descendants(header.getDocumentElement(), "paraPr")) {
            Element align = first(pp, "align");
            if (align != null) paraAligns.put(pp.getAttribute("id"), align.getAttribute("horizontal"));
        }
    }

    private String pageCss(Element sec) {
        Element pagePr = firstDescendant(sec, "pagePr");
        if (pagePr == null) return null;
        int w = parseInt(pagePr.getAttribute("width"), 59528);
        int h = parseInt(pagePr.getAttribute("height"), 84188);
        // OWPML: WIDELY=세로, NARROWLY=가로. 용지 치수는 세로 기준으로 저장된다
        if ("NARROWLY".equals(pagePr.getAttribute("landscape")) && w < h) {
            int t = w; w = h; h = t;
        }
        Element m = first(pagePr, "margin");
        String margins = m == null ? "20mm" : String.format(Locale.ROOT, "%.1fmm %.1fmm %.1fmm %.1fmm",
                mm(m.getAttribute("top")), mm(m.getAttribute("right")),
                mm(m.getAttribute("bottom")), mm(m.getAttribute("left")));
        return String.format(Locale.ROOT, "@page{size:%.1fmm %.1fmm;margin:%s;}", mm(w), mm(h), margins);
    }

    // ── 본문 ──────────────────────────────────────────────────

    /** 문단 목록(구역·셀·글상자·각주의 subList)을 블록 HTML로 */
    private void renderBlocks(Element container, StringBuilder out) {
        for (Element p : children(container, "p")) renderParagraph(p, out);
    }

    /**
     * 문단 하나. HTML의 &lt;p&gt; 안에는 표를 넣을 수 없어 표가 나오면 문단을 끊고 표를 내보낸 뒤 이어간다.
     */
    private void renderParagraph(Element p, StringBuilder out) {
        boolean pageBreak = "1".equals(p.getAttribute("pageBreak")) && !firstParagraph;
        firstParagraph = false;
        String align = cssAlign(paraAligns.get(p.getAttribute("paraPrIDRef")));
        String open = "<p style=\"" + (pageBreak ? "page-break-before:always;" : "")
                + (align == null ? "" : "text-align:" + align + ";") + "\">";

        StringBuilder inline = new StringBuilder();
        for (Element run : children(p, "run")) {
            StringBuilder runText = new StringBuilder();
            for (Node n = run.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element el)) continue;
                switch (local(el)) {
                    case "t" -> appendText(el, runText);
                    case "tbl" -> {
                        inline.append(styled(run.getAttribute("charPrIDRef"), runText.toString()));
                        runText.setLength(0);
                        flush(open, inline, out);
                        renderTable(el, out);
                    }
                    case "pic" -> runText.append(image(el));
                    case "ctrl" -> collectControl(el, runText);
                    case "equation" -> {
                        Element script = first(el, "script");
                        if (script != null) runText.append(escape(script.getTextContent().trim()));
                    }
                    case "container" -> renderShapes(el, runText);
                    default -> renderShapeText(el, runText); // 글상자(rect·ellipse 등의 drawText)
                }
            }
            inline.append(styled(run.getAttribute("charPrIDRef"), runText.toString()));
        }
        // 빈 문단도 줄을 차지하도록 남긴다(원본 줄 간격 근사)
        if (inline.isEmpty()) inline.append("&#160;");
        flush(open, inline, out);
    }

    private void flush(String open, StringBuilder inline, StringBuilder out) {
        if (inline.isEmpty()) return;
        out.append(open).append(inline).append("</p>");
        inline.setLength(0);
    }

    private void appendText(Element t, StringBuilder out) {
        for (Node n = t.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE) {
                out.append(escape(n.getNodeValue()));
            } else if (n instanceof Element el) {
                switch (local(el)) {
                    case "tab" -> out.append("&#160;&#160;&#160;&#160;");
                    case "lineBreak" -> out.append("<br/>");
                    case "nbSpace", "fwSpace" -> out.append("&#160;");
                    case "hyphen" -> out.append("-");
                    default -> out.append(escape(el.getTextContent())); // 형광펜·변경추적 등 감싸는 태그
                }
            }
        }
    }

    private void renderTable(Element tbl, StringBuilder out) {
        // 테두리는 HTML 속성으로 — LibreOffice HTML 가져오기는 td의 CSS border를 무시한다(실측)
        out.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"3\">");
        for (Element tr : children(tbl, "tr")) {
            out.append("<tr>");
            for (Element tc : children(tr, "tc")) {
                Element span = first(tc, "cellSpan");
                Element size = first(tc, "cellSz");
                out.append("<td");
                if (span != null) {
                    int cs = parseInt(span.getAttribute("colSpan"), 1);
                    int rs = parseInt(span.getAttribute("rowSpan"), 1);
                    if (cs > 1) out.append(" colspan=\"").append(cs).append('"');
                    if (rs > 1) out.append(" rowspan=\"").append(rs).append('"');
                }
                if (size != null) {
                    out.append(String.format(Locale.ROOT, " style=\"width:%.1fmm\"", mm(size.getAttribute("width"))));
                }
                out.append('>');
                Element sub = first(tc, "subList");
                if (sub != null) renderBlocks(sub, out);
                out.append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private String image(Element pic) {
        Element img = firstDescendant(pic, "img");
        if (img == null) return "";
        String href = binItems.get(img.getAttribute("binaryItemIDRef"));
        if (href == null) href = findBinData(img.getAttribute("binaryItemIDRef"));
        byte[] data = href == null ? null : entries.get(href);
        if (data == null) return "";
        String name = "img" + usedImages.size() + extension(href);
        usedImages.put(name, data);
        Element sz = first(pic, "curSz");
        if (sz == null) sz = first(pic, "sz");
        String width = sz == null ? "" : String.format(Locale.ROOT, " style=\"width:%.1fmm\"", mm(sz.getAttribute("width")));
        return "<img src=\"" + name + "\"" + width + "/>";
    }

    // manifest가 없을 때 — BinData/{id}.* 로 찾는다
    private String findBinData(String id) {
        if (id == null || id.isEmpty()) return null;
        for (String name : entries.keySet()) {
            if (name.startsWith("BinData/" + id + ".")) return name;
        }
        return null;
    }

    /** 머리말·꼬리말은 모으고(마커로 본문 앞뒤에), 각주·미주는 본문 끝으로 보낸다 */
    private void collectControl(Element ctrl, StringBuilder inline) {
        for (Element el : children(ctrl, null)) {
            String name = local(el);
            Element sub = first(el, "subList");
            if (sub == null) continue;
            switch (name) {
                case "header" -> addNote(headers, sub);
                case "footer" -> addNote(footers, sub);
                case "footNote", "endNote" -> {
                    String text = plainText(sub);
                    if (!text.isBlank()) {
                        footnotes.add(escape(text));
                        inline.append("<sup>").append(footnotes.size()).append(")</sup>");
                    }
                }
                default -> { }
            }
        }
    }

    private void addNote(LinkedHashSet<String> target, Element sub) {
        String text = plainText(sub);
        if (!text.isBlank()) target.add(text);
    }

    private void renderShapes(Element container, StringBuilder out) {
        for (Element el : children(container, null)) {
            if ("pic".equals(local(el))) out.append(image(el));
            else if ("container".equals(local(el))) renderShapes(el, out);
            else renderShapeText(el, out);
        }
    }

    // 글상자 — 도형 안 drawText/subList의 문단을 줄바꿈으로 이어 붙인다
    private void renderShapeText(Element shape, StringBuilder out) {
        Element drawText = first(shape, "drawText");
        if (drawText == null) return;
        Element sub = first(drawText, "subList");
        if (sub == null) return;
        String text = plainText(sub);
        if (!text.isBlank()) out.append("<br/>").append(escape(text).replace("\n", "<br/>")).append("<br/>");
    }

    /** subList의 문단 텍스트만 — 머리말·각주·글상자용 */
    private static String plainText(Element sub) {
        StringBuilder sb = new StringBuilder();
        for (Element p : descendants(sub, "p")) {
            StringBuilder line = new StringBuilder();
            for (Element t : descendants(p, "t")) {
                // 중첩 문단(표 안 표)의 t는 그 문단에서 따로 센다
                if (nearestParagraph(t) != p) continue;
                line.append(t.getTextContent());
            }
            String s = line.toString().strip();
            if (!s.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private static Element nearestParagraph(Node n) {
        for (Node cur = n.getParentNode(); cur != null; cur = cur.getParentNode()) {
            if (cur instanceof Element el && "p".equals(local(el))) return el;
        }
        return null;
    }

    private String styled(String charPrId, String text) {
        if (text.isEmpty()) return "";
        CharStyle cs = charStyles.get(charPrId);
        if (cs == null) return text;
        StringBuilder style = new StringBuilder();
        if (cs.heightHundredthPt() > 0 && cs.heightHundredthPt() != 1000) {
            style.append(String.format(Locale.ROOT, "font-size:%.1fpt;", cs.heightHundredthPt() / 100.0));
        }
        if (cs.bold()) style.append("font-weight:bold;");
        if (cs.italic()) style.append("font-style:italic;");
        if (cs.underline()) style.append("text-decoration:underline;");
        if (cs.color() != null && !"#000000".equalsIgnoreCase(cs.color())) style.append("color:").append(cs.color()).append(';');
        return style.isEmpty() ? text : "<span style=\"" + style + "\">" + text + "</span>";
    }

    // ── 유틸 ──────────────────────────────────────────────────

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // 업로드 파일이므로 XXE 차단
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml));
    }

    private static String local(Element el) {
        String name = el.getLocalName();
        return name != null ? name : el.getTagName().substring(el.getTagName().indexOf(':') + 1);
    }

    /** 직계 자식 중 local name이 일치하는 요소 (name이 null이면 전부) */
    private static List<Element> children(Element parent, String name) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && (name == null || name.equals(local(el)))) list.add(el);
        }
        return list;
    }

    private static Element first(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && name.equals(local(el))) return el;
        }
        return null;
    }

    private static List<Element> descendants(Element root, String name) {
        List<Element> list = new ArrayList<>();
        NodeList all = root.getElementsByTagNameNS("*", name);
        for (int i = 0; i < all.getLength(); i++) list.add((Element) all.item(i));
        return list;
    }

    private static Element firstDescendant(Element root, String name) {
        NodeList all = root.getElementsByTagNameNS("*", name);
        return all.getLength() == 0 ? null : (Element) all.item(0);
    }

    private static String cssAlign(String hwpAlign) {
        if (hwpAlign == null) return null;
        return switch (hwpAlign) {
            case "CENTER" -> "center";
            case "RIGHT" -> "right";
            case "JUSTIFY", "DISTRIBUTE", "DISTRIBUTE_SPACE" -> "justify";
            default -> null;
        };
    }

    private static String extension(String href) {
        int dot = href.lastIndexOf('.');
        return dot < 0 ? "" : href.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static double mm(String hwpUnit) {
        return mm(parseInt(hwpUnit, 0));
    }

    private static double mm(int hwpUnit) {
        return hwpUnit / HWPUNIT_PER_MM;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
