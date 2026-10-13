package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 새 문서 — 파일 없이 본문 텍스트로 작업을 만든다.
 *
 * <p>쪽 분리는 TXT 업로드와 <b>같은 규칙</b>을 쓴다(구분선 우선, 없으면 30줄 청크).
 * 두 경로가 갈리면 같은 글이 올리는 방법에 따라 다르게 잘린다 — 그래서 로직을 공유한다.
 */
class NewDocumentTest {

    private static final String SEP = "-".repeat(40);

    private JobRequestDto.NewDocument doc(String text, String fileName) {
        return new JobRequestDto.NewDocument(text, fileName, null, null, null, null, null);
    }

    // ── 쪽 분리는 TXT와 같은 규칙 ────────────────────────────────────
    // splitIntoPages는 JobService의 package-private 메서드다(같은 패키지에서 직접 본다).

    @Test
    void 구분선이_있으면_그_경계로_자른다() {
        String text = "첫 쪽 본문\n" + SEP + "\n둘째 쪽 본문";

        var chunks = JobService.splitIntoPages(text);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contains("첫 쪽 본문"));
        assertTrue(chunks.get(1).contains("둘째 쪽 본문"));
        assertFalse(chunks.get(0).contains(SEP), "구분선 자체는 본문에 남지 않는다");
    }

    @Test
    void 구분선이_없으면_30줄씩_자른다() {
        String text = String.join("\n", java.util.Collections.nCopies(65, "줄"));

        assertEquals(3, JobService.splitIntoPages(text).size(), "30 + 30 + 5");
    }

    @Test
    void 짧은_글도_한_쪽은_나온다() {
        assertEquals(1, JobService.splitIntoPages("한 줄").size());
    }

    // ── 입력 검증 ───────────────────────────────────────────────────

    @Test
    void 본문이_비면_400() {
        for (String empty : new String[]{"", "   ", "\n\n"}) {
            assertEquals(ErrorCode.COMMON_BAD_REQUEST,
                    assertThrows(CustomException.class,
                            () -> JobService.validateNewDocumentText(empty)).getErrorCode(),
                    "빈 본문: [" + empty + "]");
        }
    }

    @Test
    void 본문이_100MB를_넘으면_413() {
        // 1자 3바이트(한글) × 40M = 120MB
        String huge = "가".repeat(40 * 1024 * 1024);

        assertEquals(ErrorCode.JOB_FILE_TOO_LARGE,
                assertThrows(CustomException.class,
                        () -> JobService.validateNewDocumentText(huge)).getErrorCode(),
                "업로드 파일과 같은 상한을 쓴다");
    }

    @Test
    void 이름을_안_주면_무제가_된다() {
        assertEquals("무제", JobService.resolveNewDocumentName(doc("본문", null)));
        assertEquals("무제", JobService.resolveNewDocumentName(doc("본문", "   ")));
        assertEquals("중학 수학 1단원", JobService.resolveNewDocumentName(doc("본문", " 중학 수학 1단원 ")));
    }
}
