package com.semojum.backend.domain.job.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** original은 그 모드에 해당 없는 필드를 아예 내보내지 않는다 (a·c는 lines 없음, b는 url 없음) */
class OriginalContentJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 이미지_원본은_url만_나가고_lines_키는_없다() throws Exception {
        String json = mapper.writeValueAsString(
                new JobResponseDto.OriginalContent("image", "https://signed/page-1.jpg", null));

        assertTrue(json.contains("\"type\":\"image\""));
        assertTrue(json.contains("\"url\":\"https://signed/page-1.jpg\""));
        assertFalse(json.contains("lines"), "a·c엔 lines 키가 없어야 한다: " + json);
    }

    @Test
    void 텍스트_원본은_lines만_나가고_url_키는_없다() throws Exception {
        String json = mapper.writeValueAsString(
                new JobResponseDto.OriginalContent("text", null, List.of("첫 줄", "둘째 줄")));

        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"lines\""));
        assertFalse(json.contains("url"), "b엔 url 키가 없어야 한다: " + json);
    }

    @Test
    void 빈_줄도_보존된다() throws Exception {
        String json = mapper.writeValueAsString(
                new JobResponseDto.OriginalContent("text", null, List.of("첫 줄", "", "셋째 줄")));

        assertEquals("{\"type\":\"text\",\"lines\":[\"첫 줄\",\"\",\"셋째 줄\"]}", json);
    }
}
