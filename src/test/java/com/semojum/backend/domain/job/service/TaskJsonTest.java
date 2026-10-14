package com.semojum.backend.domain.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공정 스케줄러 태스크 JSON — 워커가 꺼내는 키 이름을 고정한다.
 *
 * <p>키 이름이 틀려도 컴파일은 통과하고 생성 API도 200을 준다. 깨지는 곳은 워커라서
 * 쪽이 전부 BLOCKED가 된 뒤에야 드러난다 — 실제로 9d20f81에서 텍스트 경로의 {@code mode} 키가
 * 상수 이름으로 치환돼 TXT 업로드와 「새 문서」가 운영에서 전부 실패했다.
 */
class TaskJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private Map<String, Object> parse(String mode, String sourcePath) throws Exception {
        return MAPPER.readValue(
                JobService.taskJson("job-1", 3, sourcePath, mode, 7, USER, true,
                        new com.semojum.backend.domain.job.dto.BrailleOptions(
                                "grade1", "grade2", null, null, null, null, "onePerLine", null)), Map.class);
    }

    /** 워커가 꺼내는 키가 하나라도 빠지면 그 쪽은 처리되지 않는다 */
    @Test
    void 워커가_읽는_키를_모두_담는다() throws Exception {
        Map<String, Object> task = parse("c", "job-1/pages/page-3.pdf");

        for (String key : new String[]{"jobId", "pageNo", "sourcePath", "mode", "totalPages", "userId", "advancedAi",
                "koreanGrade", "englishGrade", "splitChoices"}) {
            assertTrue(task.containsKey(key), "태스크에 " + key + " 키가 없다");
        }
    }

    /** 워커는 mode로 PDF 전송과 텍스트 전송을 가른다 — null이면 NPE로 재시도만 소모한다 */
    @Test
    void 텍스트_작업의_mode를_그대로_담는다() throws Exception {
        assertEquals(JobService.MODE_TEXT, parse(JobService.MODE_TEXT, "job-1/pages/page-3.txt").get("mode"));
    }

    @Test
    void 파일_작업의_mode를_그대로_담는다() throws Exception {
        assertEquals("c", parse("c", "job-1/pages/page-3.pdf").get("mode"));
    }

    @Test
    void 나머지_값도_그대로_담는다() throws Exception {
        Map<String, Object> task = parse("c", "job-1/pages/page-3.pdf");

        assertEquals("job-1", task.get("jobId"));
        assertEquals("job-1/pages/page-3.pdf", task.get("sourcePath"));
        assertEquals(3, task.get("pageNo"));
        assertEquals(7, task.get("totalPages"));
        assertEquals(USER.toString(), task.get("userId"));
        assertEquals(true, task.get("advancedAi"));
        assertEquals(1, task.get("koreanGrade"));      // grade1 → 1
        assertEquals(2, task.get("englishGrade"));     // grade2 → 2 (proto english_grade)
        assertEquals(true, task.get("splitChoices"));  // onePerLine → true
    }

    /** 점역 옵션 → proto korean_grade · split_choices. 모르는 값이나 null은 서버 기본(0·false) */
    @Test
    void 점역_옵션을_AI_값으로_옮긴다() {
        var grade1 = new com.semojum.backend.domain.job.dto.BrailleOptions("grade1", null, null, null, null, null, null, null);
        var grade2 = new com.semojum.backend.domain.job.dto.BrailleOptions("grade2", null, null, null, null, null, "merge", null);

        assertEquals(1, JobService.koreanGradeOf(grade1));
        assertEquals(2, JobService.koreanGradeOf(grade2));
        assertEquals(0, JobService.koreanGradeOf(null));
        assertEquals(false, JobService.splitChoicesOf(grade2));
        assertEquals(false, JobService.splitChoicesOf(null));
    }
}
