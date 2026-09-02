package com.semojum.brailleassist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** vectors.json 대조 — java 구현. 세 언어가 같은 벡터로 같은 출력을 내야 한다. */
class VectorsTest {

    /** vectors.json은 언어 중립으로 snake_case를 쓴다 — java는 camelCase라 여기서 옮긴다. */
    private static BrailleAssist.Options toOpts(JsonNode o) {
        if (o == null || o.isNull()) return BrailleAssist.Options.defaults();
        return new BrailleAssist.Options(
                o.get("cols").asInt(),
                o.get("rows").asInt(),
                o.get("show_orig_page").asBoolean(),
                o.get("show_braille_page").asBoolean(),
                o.get("page_row_on").asText(),
                o.get("cover_pages").asInt(),
                o.has("orig_page_start") && !o.get("orig_page_start").isNull()
                        ? o.get("orig_page_start").asInt() : null,
                !o.has("show_change_line") || o.get("show_change_line").asBoolean(),
                o.has("footer_align") ? o.get("footer_align").asText() : "center");
    }

    /** 면 배열 → 비교용 문자열. 줄은 \u241F, 면은 \u241E로 잇는다(점자에 안 쓰이는 문자). */
    private static String join(List<List<String>> pages) {
        StringBuilder sb = new StringBuilder();
        for (List<String> p : pages) {
            if (sb.length() > 0) sb.append('\u241E');
            sb.append(String.join("\u241F", p));
        }
        return sb.toString();
    }

    private static String joinNode(JsonNode pages) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : pages) {
            if (sb.length() > 0) sb.append('\u241E');
            List<String> ls = new ArrayList<>();
            for (JsonNode l : p) ls.add(l.asText());
            sb.append(String.join("\u241F", ls));
        }
        return sb.toString();
    }

    private static String call(String fname, JsonNode a) {
        switch (fname) {
            case "page_row":
                return BrailleAssist.pageRow(
                        a.get("orig_page").asInt(),
                        a.get("cont_idx").asInt(),
                        a.get("braille_page").asInt(),
                        a.has("footer") ? a.get("footer").asText() : "",
                        toOpts(a.get("opts")));
            case "page_change_line":
                return BrailleAssist.pageChangeLine(a.get("orig_page").asInt(), toOpts(a.get("opts")));
            case "to_brf_ascii":
                return BrailleAssist.toBrfAscii(a.get("braille").asText());
            case "build_pages": {
                List<BrailleAssist.Source> srcs = new ArrayList<>();
                for (JsonNode s : a.get("sources")) {
                    List<BrailleAssist.Block> bs = new ArrayList<>();
                    for (JsonNode b : s.get("blocks")) {
                        bs.add(new BrailleAssist.Block(b.get("order").asInt(), b.get("text").asText()));
                    }
                    srcs.add(new BrailleAssist.Source(s.get("orig_page").asInt(), bs));
                }
                // 면 배열은 JSON으로 견줘야 해서 문자열로 직렬화해 비교한다.
                java.util.Map<Integer, String> footers = new java.util.HashMap<>();
                if (a.has("footers") && !a.get("footers").isNull()) {
                    JsonNode fs = a.get("footers");
                    fs.fieldNames().forEachRemaining(
                            k -> footers.put(Integer.parseInt(k), fs.get(k).asText()));
                }
                return join(BrailleAssist.buildPages(srcs,
                        a.has("footer") ? a.get("footer").asText() : "",
                        a.has("start_braille_page") ? a.get("start_braille_page").asInt() : 1,
                        toOpts(a.get("opts")), footers));
            }
            case "build_brf": {
                JsonNode j = a.get("job");
                JsonNode o = j.has("options") ? j.get("options") : null;
                List<BrailleAssist.JobPage> pages = new ArrayList<>();
                if (j.has("pages")) {
                    for (JsonNode pg : j.get("pages")) {
                        List<BrailleAssist.JobElement> els = new ArrayList<>();
                        if (pg.has("elements")) {
                            for (JsonNode el : pg.get("elements")) {
                                els.add(new BrailleAssist.JobElement(
                                        el.has("id") ? el.get("id").asText() : null,
                                        el.has("type") ? el.get("type").asText() : "text",
                                        el.has("heading_level") ? el.get("heading_level").asInt() : 0,
                                        el.get("text").asText()));
                            }
                        }
                        pages.add(new BrailleAssist.JobPage(
                                pg.has("orig_page_no") ? pg.get("orig_page_no").asInt() : 0, els));
                    }
                }
                java.util.Map<Integer, String> jf = new java.util.HashMap<>();
                if (j.has("footers_braille") && !j.get("footers_braille").isNull()) {
                    JsonNode fs = j.get("footers_braille");
                    fs.fieldNames().forEachRemaining(
                            k -> jf.put(Integer.parseInt(k), fs.get(k).asText()));
                }
                BrailleAssist.Job job = new BrailleAssist.Job(
                        j.has("job_id") ? j.get("job_id").asText() : null,
                        o == null || !o.has("include_page_number")
                                || o.get("include_page_number").asBoolean(),
                        o != null && o.has("page_row_on") ? o.get("page_row_on").asText() : "odd",
                        o != null && o.has("rows") ? o.get("rows").asInt() : 26,
                        o != null && o.has("cols") ? o.get("cols").asInt() : 32,
                        o == null || !o.has("show_orig_page") || o.get("show_orig_page").asBoolean(),
                        o == null || !o.has("show_braille_page")
                                || o.get("show_braille_page").asBoolean(),
                        o != null && o.has("cover_pages") ? o.get("cover_pages").asInt() : 0,
                        o != null && o.has("orig_page_start") && !o.get("orig_page_start").isNull()
                                ? o.get("orig_page_start").asInt() : null,
                        o == null || !o.has("show_change_line")
                                || o.get("show_change_line").asBoolean(),
                        o != null && o.has("footer_align") ? o.get("footer_align").asText() : "center",
                        j.has("footer_braille") ? j.get("footer_braille").asText() : "",
                        jf,
                        j.has("start_braille_page") ? j.get("start_braille_page").asInt() : 1,
                        pages);
                return BrailleAssist.buildBrf(job);
            }
            default:
                throw new IllegalArgumentException("모르는 함수: " + fname);
        }
    }

    @TestFactory
    List<DynamicTest> vectors() throws Exception {
        // BE는 원 레포 디렉터리 구조가 아니라 클래스패스 리소스로 벡터를 둔다 (로컬 적응)
        Path p = Path.of(VectorsTest.class.getResource("/braille-assist/vectors.json").toURI());
        JsonNode root = new ObjectMapper().readTree(Files.readString(p));
        List<DynamicTest> tests = new ArrayList<>();
        root.get("cases").fields().forEachRemaining(e -> {
            String fname = e.getKey();
            for (JsonNode c : e.getValue()) {
                tests.add(DynamicTest.dynamicTest(
                        fname + " — " + c.get("name").asText(),
                        () -> assertEquals(
                                fname.equals("build_pages") ? joinNode(c.get("expect"))
                                                            : c.get("expect").asText(),
                                call(fname, c.get("args")))));
            }
        });
        return tests;
    }
}
