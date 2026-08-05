package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** FE 응답이 AI 변환 결과(proto V3.0.0)를 그대로 반영하는지 검증 */
class PageResultSerializerTest {

    TextElementRepository textRepo;
    BrailleElementRepository brailleRepo;
    BoundingBoxRepository boxRepo;
    RuleTrailRepository ruleRepo;
    QualityCriticalErrorRepository errorRepo;
    QualityReviewFlagRepository flagRepo;
    PageResultSerializer serializer;

    @BeforeEach
    void setUp() {
        textRepo = Mockito.mock(TextElementRepository.class);
        brailleRepo = Mockito.mock(BrailleElementRepository.class);
        boxRepo = Mockito.mock(BoundingBoxRepository.class);
        ruleRepo = Mockito.mock(RuleTrailRepository.class);
        errorRepo = Mockito.mock(QualityCriticalErrorRepository.class);
        flagRepo = Mockito.mock(QualityReviewFlagRepository.class);
        serializer = new PageResultSerializer(textRepo, brailleRepo, boxRepo, ruleRepo, errorRepo, flagRepo);

        when(ruleRepo.findByElementId(any())).thenReturn(List.of());
        when(errorRepo.findByPageResult(any())).thenReturn(List.of());
        when(flagRepo.findByPageResult(any())).thenReturn(List.of());
        when(boxRepo.findByPageResult(any())).thenReturn(List.of());
        when(textRepo.findByPageResult(any())).thenReturn(List.of());
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of());
    }

    private PageResult pageResult(String mode) {
        return PageResult.builder()
                .pageNumber(1).mode(mode).status("COMPLETED")
                .imageWidth("b".equals(mode) ? null : 1224)
                .imageHeight("b".equals(mode) ? null : 1584)
                .ocrConfidenceAvg(1.0)
                .processingTimeMs(11).routingTierUsed("ZERO").pdfLayerConfidence(0.9).scanOnly(false)
                .build();
    }

    /** AI가 빈 값을 주면 DB엔 null로 들어가지만, FE엔 빈 배열로 나가야 한다(널 체크 없이 순회 가능) */
    @Test
    void repeated_필드는_null이_아니라_빈배열로_나간다() {
        TextElement el = TextElement.builder()
                .elementId("el-1").type("text").readingOrder(1)
                .contents(null)   // AI가 안 준 경우
                .drafts(null)     // 텍스트 요소라 초안 없음
                .build();
        when(textRepo.findByPageResult(any())).thenReturn(List.of(el));
        when(boxRepo.findByPageResult(any())).thenReturn(List.of(
                BoundingBox.builder().elementId("el-1").x(1).y(2).x2(3).y2(4).type("text").flags(null).build()));

        Map<String, Object> result = serializer.buildResult(pageResult("a"));

        Map<?, ?> textEl = ((List<Map<?, ?>>) result.get("text_list")).get(0);
        assertEquals(List.of(), textEl.get("drafts"), "drafts는 빈 배열");
        assertEquals(List.of(), textEl.get("contents"), "contents는 빈 배열");
        assertEquals(List.of(), textEl.get("rule_trail"), "rule_trail은 빈 배열");
        Map<?, ?> box = ((List<Map<?, ?>>) result.get("bounding_box_list")).get(0);
        assertEquals(List.of(), box.get("flags"), "flags는 빈 배열");
    }

    /** AI는 mode c에도 원문(text_list)을 주고 BE가 저장한다 — 응답에서 누락되면 안 된다 */
    @Test
    void mode_c도_원문_text_list를_내보낸다() {
        when(textRepo.findByPageResult(any())).thenReturn(List.of(
                TextElement.builder().elementId("el-1").contents(List.of("원문 텍스트")).build()));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(
                BrailleElement.builder().elementId("el-1").content(List.of("⠚⠒")).build()));

        Map<String, Object> result = serializer.buildResult(pageResult("c"));

        List<Map<?, ?>> textList = (List<Map<?, ?>>) result.get("text_list");
        assertEquals(1, textList.size());
        assertEquals("el-1", textList.get(0).get("id"));
        assertEquals(List.of("원문 텍스트"), textList.get(0).get("contents"));
        // 점자 요소와 같은 id로 1:1 매칭된다
        List<Map<?, ?>> brailleList = (List<Map<?, ?>>) result.get("braille_text_list");
        assertEquals(textList.get(0).get("id"), brailleList.get(0).get("id"));
    }

    /** proto 08-05에서 폐기 — 항상 null이던 필드를 계속 내보내지 않는다 */
    @Test
    void 폐기된_line_overflow_rate는_내보내지_않는다() {
        Map<String, Object> result = serializer.buildResult(pageResult("b"));
        Map<?, ?> report = (Map<?, ?>) result.get("quality_report");

        assertFalse(report.containsKey("line_overflow_rate"));
        assertTrue(report.containsKey("ocr_confidence_avg"));
        assertTrue(report.containsKey("critical_errors"));
        assertTrue(report.containsKey("review_flags"));
    }

    /** AI가 주지만 저장만 하고 FE엔 안 나가던 값 */
    @Test
    void processing_meta를_내보낸다() {
        Map<String, Object> result = serializer.buildResult(pageResult("a"));
        Map<?, ?> meta = (Map<?, ?>) result.get("processing_meta");

        assertEquals(11, meta.get("processing_time_ms"));
        assertEquals("ZERO", meta.get("routing_tier_used"));
        assertEquals(0.9, meta.get("pdf_layer_confidence"));
        assertEquals(false, meta.get("scan_only"));
    }

    /** 이미지 정보는 AI가 주는 mode a·c에만 (mode b는 이미지 자체가 없음) */
    @Test
    void 이미지_정보는_mode_a_c에만_나간다() {
        Map<String, Object> modeA = serializer.buildResult(pageResult("a"));
        assertTrue(modeA.containsKey("image_resolution"));
        assertTrue(modeA.containsKey("bounding_box_list"));

        Map<String, Object> modeB = serializer.buildResult(pageResult("b"));
        assertFalse(modeB.containsKey("image_resolution"));
        assertFalse(modeB.containsKey("bounding_box_list"));
        assertTrue(modeB.containsKey("braille_text_list"));
    }

    /** mode a는 결과물이 텍스트라 전체 필드, b·c는 원문 대조용이라 id+contents만 */
    @Test
    void mode별_text_list_상세도가_다르다() {
        when(textRepo.findByPageResult(any())).thenReturn(List.of(
                TextElement.builder().elementId("el-1").type("text").readingOrder(1)
                        .contents(List.of("텍스트")).build()));

        Map<?, ?> aEl = ((List<Map<?, ?>>) serializer.buildResult(pageResult("a")).get("text_list")).get(0);
        assertTrue(aEl.containsKey("rule_trail"));
        assertTrue(aEl.containsKey("is_blocked"));

        Map<?, ?> bEl = ((List<Map<?, ?>>) serializer.buildResult(pageResult("b")).get("text_list")).get(0);
        assertEquals(2, bEl.size(), "원문은 id + contents만");
        assertTrue(bEl.containsKey("id") && bEl.containsKey("contents"));
    }
}
