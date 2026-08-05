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

    /** mode c는 좌측이 PDF 이미지라 원문 텍스트가 화면에 안 쓰임 — AI가 줘도 응답에서 뺀다 */
    @Test
    void mode_c는_원문_text_list를_내보내지_않는다() {
        when(textRepo.findByPageResult(any())).thenReturn(List.of(
                TextElement.builder().elementId("el-1").contents(List.of("중간 산물 원문")).build()));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(
                BrailleElement.builder().elementId("el-1").content(List.of("⠚⠒")).build()));

        Map<String, Object> result = serializer.buildResult(pageResult("c"));

        assertFalse(result.containsKey("text_list"), "mode c엔 text_list 없음");
        assertTrue(result.containsKey("braille_text_list"));
        assertTrue(result.containsKey("bounding_box_list"), "좌측 이미지 대조용 bbox는 필요");
    }

    /** 화면에 쓰지 않는 지표는 빼고, 점역사에게 보여줄 항목만 남긴다 */
    @Test
    void quality_report는_오류_검토항목만_담는다() {
        Map<String, Object> result = serializer.buildResult(pageResult("b"));
        Map<?, ?> report = (Map<?, ?>) result.get("quality_report");

        assertFalse(report.containsKey("line_overflow_rate"), "proto 08-05 폐기");
        assertFalse(report.containsKey("ocr_confidence_avg"), "화면에 쓰지 않는 내부 지표");
        assertTrue(report.containsKey("critical_errors"), "점역사에게 보여줄 오류");
        assertTrue(report.containsKey("review_flags"), "점역사 검토 항목");
    }

    /** AI가 주지만 에디터 화면에서 쓰지 않는 내부 메타 — 내보내지 않는다 */
    @Test
    void processing_meta는_내보내지_않는다() {
        Map<String, Object> result = serializer.buildResult(pageResult("a"));
        assertFalse(result.containsKey("processing_meta"));
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

    /** mode a는 결과물이 텍스트라 전체 필드, b는 원문 대조용이라 id+contents만 */
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
