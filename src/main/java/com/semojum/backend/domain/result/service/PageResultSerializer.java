package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 페이지 변환 결과 → FE 응답 직렬화. SSE(page_done)와 마이페이지 상세가 공유한다.
 * (기존에 SseService·UserService에 같은 코드가 중복돼 있어 한쪽만 고쳐지는 위험이 있었다)
 *
 * <p>기준은 <b>AI가 준 변환 결과(proto V3.0.0)</b>다. AI가 채워 보낸 값은 가공 없이 그대로 내보내고,
 * proto에서 폐기된 필드는 내보내지 않는다.
 *
 * <p>proto의 repeated 필드(contents·drafts·flags·rule_trail)는 <b>항상 배열</b>로 내보낸다 —
 * DB에는 빈 값이 null로 저장되지만 FE가 null 체크 없이 순회할 수 있어야 한다.
 */
@Component
@RequiredArgsConstructor
public class PageResultSerializer {

    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;

    /**
     * 모드별 result 구성.
     * - a: 이미지→텍스트  · 결과물은 text_list
     * - b: 텍스트→점자    · 결과물은 braille_text_list, text_list는 원문(대조용, id로 1:1 매칭)
     * - c: 이미지→점자    · 결과물은 braille_text_list, text_list는 중간 산물 원문(대조용)
     * 이미지 정보(image_resolution·bounding_box_list)는 AI가 주는 mode a·c에만 넣는다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildResult(PageResult pageResult) {
        String mode = pageResult.getMode();
        Map<String, Object> result = new LinkedHashMap<>();

        List<TextElement> textElements = textElementRepository.findByPageResult(pageResult);
        List<BrailleElement> brailleElements = brailleElementRepository.findByPageResult(pageResult);
        List<BoundingBox> boundingBoxes = boundingBoxRepository.findByPageResult(pageResult);
        List<QualityCriticalError> criticalErrors = qualityCriticalErrorRepository.findByPageResult(pageResult);
        List<QualityReviewFlag> reviewFlags = qualityReviewFlagRepository.findByPageResult(pageResult);

        boolean hasImage = "a".equals(mode) || "c".equals(mode);
        if (hasImage && pageResult.getImageWidth() != null) {
            Map<String, Object> imgRes = new LinkedHashMap<>();
            imgRes.put("width", pageResult.getImageWidth());
            imgRes.put("height", pageResult.getImageHeight());
            result.put("image_resolution", imgRes);
        }
        if (hasImage) {
            result.put("bounding_box_list", buildBoundingBoxList(boundingBoxes));
        }

        if ("a".equals(mode)) {
            // 결과물이므로 전체 필드
            result.put("text_list", buildTextListFull(textElements));
        } else {
            // b·c: 원문은 대조·매칭용이라 id + contents만. 점자가 결과물
            result.put("text_list", buildTextListSimple(textElements));
            result.put("braille_text_list", buildBrailleListFull(brailleElements));
        }

        result.put("quality_report", buildQualityReport(pageResult, criticalErrors, reviewFlags));
        result.put("processing_meta", buildProcessingMeta(pageResult));
        return result;
    }

    private List<Map<String, Object>> buildTextListFull(List<TextElement> elements) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TextElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("type", el.getType());
            map.put("order", el.getReadingOrder());
            map.put("heading_level", el.getHeadingLevel());
            map.put("tn_text", el.getTnText());
            map.put("latex_string", el.getLatexString());
            map.put("selected_idx", el.getSelectedIdx());
            map.put("render_mode", el.getRenderMode());
            map.put("visual_subtype", el.getVisualSubtype());
            map.put("contents", orEmpty(el.getCurrentContents()));
            map.put("drafts", orEmpty(el.getDrafts()));
            map.put("is_blocked", el.isBlocked());
            map.put("rule_trail", buildRuleTrailList(el.getId()));
            list.add(map);
        }
        return list;
    }

    /** 원문 대조용 — 점자 요소와 같은 id로 짝지어진다 */
    private List<Map<String, Object>> buildTextListSimple(List<TextElement> elements) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TextElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("contents", orEmpty(el.getCurrentContents()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildBrailleListFull(List<BrailleElement> elements) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BrailleElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("type", el.getType());
            map.put("order", el.getReadingOrder());
            map.put("heading_level", el.getHeadingLevel());
            map.put("tn_text", el.getTnText());
            map.put("latex_string", el.getLatexString());
            map.put("selected_idx", el.getSelectedIdx());
            map.put("render_mode", el.getRenderMode());
            map.put("visual_subtype", el.getVisualSubtype());
            map.put("contents", orEmpty(el.getCurrentContent()));
            map.put("drafts", orEmpty(el.getDrafts()));
            map.put("is_blocked", el.isBlocked());
            map.put("rule_trail", buildRuleTrailList(el.getId()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildBoundingBoxList(List<BoundingBox> boxes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BoundingBox box : boxes) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", box.getElementId());
            map.put("x", box.getX());
            map.put("y", box.getY());
            map.put("x2", box.getX2());
            map.put("y2", box.getY2());
            map.put("type", box.getType());
            map.put("heading_level", box.getHeadingLevel());
            map.put("caption_ref", box.getCaptionRef());
            map.put("flags", orEmpty(box.getFlags()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildRuleTrailList(UUID elementId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RuleTrail rt : ruleTrailRepository.findByElementId(elementId)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rule_id", rt.getRuleId());
            map.put("source", rt.getSource());
            map.put("priority", rt.getPriority());
            map.put("section", rt.getSection());
            map.put("title", rt.getTitle());
            map.put("excerpt", rt.getExcerpt());
            map.put("line_no", rt.getLineNo());
            map.put("col_start", rt.getColStart());
            map.put("col_end", rt.getColEnd());
            map.put("tag", rt.getTag());
            list.add(map);
        }
        return list;
    }

    // proto 08-05에서 line_overflow_rate가 폐기됨(32칸 초과 판정은 조판하는 쪽 담당) → 내보내지 않는다
    private Map<String, Object> buildQualityReport(PageResult pageResult,
                                                   List<QualityCriticalError> criticalErrors,
                                                   List<QualityReviewFlag> reviewFlags) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ocr_confidence_avg", pageResult.getOcrConfidenceAvg());

        List<Map<String, Object>> errors = new ArrayList<>();
        for (QualityCriticalError e : criticalErrors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.getType());
            m.put("element_id", e.getElementId());
            m.put("message", e.getMessage());
            errors.add(m);
        }
        report.put("critical_errors", errors);

        List<Map<String, Object>> flags = new ArrayList<>();
        for (QualityReviewFlag f : reviewFlags) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", f.getType());
            m.put("element_id", f.getElementId());
            m.put("message", f.getMessage());
            flags.add(m);
        }
        report.put("review_flags", flags);

        return report;
    }

    /** AI가 준 처리 메타(proto ProcessingMeta) — 저장만 하고 FE엔 안 나가던 값 */
    private Map<String, Object> buildProcessingMeta(PageResult pageResult) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("processing_time_ms", pageResult.getProcessingTimeMs());
        meta.put("pdf_layer_confidence", pageResult.getPdfLayerConfidence());
        meta.put("routing_tier_used", pageResult.getRoutingTierUsed());
        meta.put("scan_only", pageResult.getScanOnly());
        return meta;
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? Collections.emptyList() : value;
    }
}
