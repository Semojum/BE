package com.semojum.backend.global.hwp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// 실제 HWP/HWPX 파일의 페이지 분리 결과를 검증하기 위한 수동 디버그 도구.
// 실행: HWP_DEBUG_FILE=<경로> HWP_DEBUG_OUT=<출력 경로> ./gradlew test --tests HwpPageExtractorDebugTest
@EnabledIfEnvironmentVariable(named = "HWP_DEBUG_FILE", matches = ".+")
class HwpPageExtractorDebugTest {

    @Test
    void 페이지_분리_결과와_레이아웃_원본을_덤프한다() throws Exception {
        String file = System.getenv("HWP_DEBUG_FILE");
        String out = System.getenv().getOrDefault("HWP_DEBUG_OUT", "/tmp/hwp_debug.txt");
        byte[] bytes = Files.readAllBytes(new File(file).toPath());
        boolean isHwpx = bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';

        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
            w.println("format: " + (isHwpx ? "HWPX(zip)" : "HWP(cfb)"));

            // 1) 현재 추출기의 페이지 분리 결과
            HwpPageExtractor extractor = new HwpPageExtractor();
            List<String> pages;
            try (FileInputStream in = new FileInputStream(file)) {
                pages = extractor.extractPages(in);
            }
            w.println("===== 추출 결과: 총 " + pages.size() + "페이지 =====");
            for (int i = 0; i < pages.size(); i++) {
                String p = pages.get(i);
                w.println("\n----- [페이지 " + (i + 1) + "] " + p.length() + "자, "
                        + p.lines().count() + "줄 -----");
                w.println(p);
            }

            // 2) HWPX 레이아웃 원본 — 문단별 lineseg vertpos@textpos
            if (isHwpx) {
                w.println("\n\n===== HWPX 레이아웃 원본 =====");
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (!entry.getName().matches("Contents/section\\d+\\.xml")) continue;
                        w.println("--- " + entry.getName() + " ---");
                        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                        f.setNamespaceAware(true);
                        Document doc = f.newDocumentBuilder()
                                .parse(new ByteArrayInputStream(zip.readAllBytes()));
                        int idx = 0;
                        for (Element p : children(doc.getDocumentElement(), "p")) {
                            StringBuilder ys = new StringBuilder();
                            for (Element arr : children(p, "linesegarray")) {
                                for (Element seg : children(arr, "lineseg")) {
                                    ys.append(seg.getAttribute("vertpos")).append("@")
                                      .append(seg.getAttribute("textpos")).append(" ");
                                }
                            }
                            String text = directText(p);
                            w.printf("[%3d] y=[%s] text(%d)=%s%n", idx++, ys.toString().trim(),
                                    text.length(),
                                    text.substring(0, Math.min(60, text.length())).replace("\n", "⏎"));
                        }
                    }
                }
            }
        }
        System.out.println("dumped to " + out);
    }

    private List<Element> children(Element parent, String localName) {
        java.util.ArrayList<Element> result = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private String directText(Element p) {
        StringBuilder sb = new StringBuilder();
        for (Element run : children(p, "run")) {
            for (Element t : children(run, "t")) {
                sb.append(t.getTextContent());
            }
        }
        return sb.toString();
    }
}
