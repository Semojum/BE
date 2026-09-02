package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.result.entity.BrailleElement;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.TextElement;
import com.semojum.backend.domain.result.repository.BrailleElementRepository;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.domain.result.repository.TextElementRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 다운로드 파일 생성 — mode a 텍스트 병합 / mode b·c braille-assist 조판 위임 검증 */
class JobDownloadServiceTest {

    JobRepository jobRepo;
    PageResultRepository pageResultRepo;
    TextElementRepository textRepo;
    BrailleElementRepository brailleRepo;
    BrailleGrpcClient grpc;
    FooterBrailleService footerBrailleService;
    JobDownloadService service;

    final String USER = UUID.randomUUID().toString();
    Job job;

    @BeforeEach
    void setUp() {
        jobRepo = Mockito.mock(JobRepository.class);
        pageResultRepo = Mockito.mock(PageResultRepository.class);
        textRepo = Mockito.mock(TextElementRepository.class);
        brailleRepo = Mockito.mock(BrailleElementRepository.class);
        grpc = Mockito.mock(BrailleGrpcClient.class);
        footerBrailleService = Mockito.mock(FooterBrailleService.class);
        service = new JobDownloadService(jobRepo, pageResultRepo, textRepo, brailleRepo, grpc,
                footerBrailleService);
    }

    private Job givenJob(String mode, boolean insertPageNumber, String footerText) {
        job = Job.builder().id("job1").mode(mode).totalPages(2).originalFileName("원본문서.pdf")
                .insertPageNumber(insertPageNumber).footerText(footerText).build();
        job.updateStatus("COMPLETED");
        when(jobRepo.findByIdAndUserId(eq("job1"), any())).thenReturn(Optional.of(job));
        return job;
    }

    private PageResult pr(int pageNo, String mode) {
        return PageResult.builder().pageNumber(pageNo).mode(mode).status("COMPLETED").build();
    }

    /** mode a 페이지 구분선 (서비스 상수와 동일해야 함) */
    static final String SEP = "-".repeat(40);

    /** mode a — 요소는 줄바꿈, 페이지는 하이픈 구분선 1줄로 병합. 점역자주 마커는 그대로 */
    @Test
    void mode_a는_요소와_페이지를_순서대로_병합한_txt() {
        givenJob("a", false, null);
        PageResult p1 = pr(1, "a"), p2 = pr(2, "a");
        when(pageResultRepo.findByJobIdOrderByPageNumber("job1")).thenReturn(List.of(p1, p2));
        when(textRepo.findByPageResult(p1)).thenReturn(List.of(
                TextElement.builder().elementId("e1").contents(List.of("첫 문단")).build(),
                TextElement.builder().elementId("e2").contents(List.of("<!점역자주>그림 설명<!/점역자주>")).build()));
        when(textRepo.findByPageResult(p2)).thenReturn(List.of(
                TextElement.builder().elementId("e3").contents(List.of("둘째 쪽")).build()));

        JobDownloadService.DownloadFile f = service.download(USER, "job1", null);

        assertEquals("원본문서.txt", f.fileName());
        assertEquals("첫 문단\n<!점역자주>그림 설명<!/점역자주>\n" + SEP + "\n둘째 쪽",
                new String(f.content(), StandardCharsets.UTF_8));
        verify(grpc, never()).translateText(anyString());
    }

    /** 내용만 비운 블록은 빈 줄로 남지 않고 뒤 내용이 당겨진다 (QA 0808 — 삭제 자리 공란 버그) */
    @Test
    void 내용이_빈_블록은_건너뛴다() {
        givenJob("a", false, null);
        PageResult p1 = pr(1, "a");
        when(pageResultRepo.findByJobIdOrderByPageNumber("job1")).thenReturn(List.of(p1));
        when(textRepo.findByPageResult(p1)).thenReturn(List.of(
                TextElement.builder().elementId("e1").contents(List.of("")).build(),       // 내용 전부 삭제
                TextElement.builder().elementId("e2").contents(List.of("  ")).build(),     // 공백만 남음
                TextElement.builder().elementId("e3").contents(List.of()).build(),         // 빈 배열
                TextElement.builder().elementId("e4").contents(List.of("셋째 줄 내용")).build()));

        JobDownloadService.DownloadFile f = service.download(USER, "job1", null);

        assertEquals("셋째 줄 내용", new String(f.content(), StandardCharsets.UTF_8),
                "빈 블록들이 빈 줄로 남지 않고 남은 내용부터 시작");
    }

    /** 블록이 전부 빈 페이지는 구분선도 남기지 않는다 */
    @Test
    void 전부_빈_페이지는_구분선도_없다() {
        givenJob("a", false, null);
        PageResult p1 = pr(1, "a"), p2 = pr(2, "a"), p3 = pr(3, "a");
        when(pageResultRepo.findByJobIdOrderByPageNumber("job1")).thenReturn(List.of(p1, p2, p3));
        when(textRepo.findByPageResult(p1)).thenReturn(List.of(
                TextElement.builder().elementId("e1").contents(List.of("첫 페이지")).build()));
        when(textRepo.findByPageResult(p2)).thenReturn(List.of(          // 2페이지: 내용 전부 삭제됨
                TextElement.builder().elementId("e2").contents(List.of("")).build()));
        when(textRepo.findByPageResult(p3)).thenReturn(List.of(
                TextElement.builder().elementId("e3").contents(List.of("셋째 페이지")).build()));

        JobDownloadService.DownloadFile f = service.download(USER, "job1", null);

        assertEquals("첫 페이지\n" + SEP + "\n셋째 페이지", new String(f.content(), StandardCharsets.UTF_8),
                "빈 페이지가 통째로 빠지고 남은 페이지끼리 구분선 1줄로 구분");
    }

    /** mode b — braille-assist에 위임: BRF-ASCII 26줄 면, 쪽번호 끄면 페이지행 없음 */
    @Test
    void mode_b는_braille_assist로_조판한_brf() {
        givenJob("b", false, null);
        PageResult p1 = pr(1, "b");
        when(pageResultRepo.findByJobIdOrderByPageNumber("job1")).thenReturn(List.of(p1));
        when(brailleRepo.findByPageResult(p1)).thenReturn(List.of(
                BrailleElement.builder().elementId("b1").content(List.of("⠼⠁⠃")).build()));

        JobDownloadService.DownloadFile f = service.download(USER, "job1", null);

        String[] lines = new String(f.content(), StandardCharsets.UTF_8).split("\n", -1);
        assertEquals("원본문서.brf", f.fileName());
        assertEquals("#ab", lines[0], "BRF-ASCII 변환");
        assertEquals(26, lines.length, "26줄 면 규격 (빈 줄 패딩 포함)");
        assertTrue(lines[25].isEmpty(), "쪽번호 off → 페이지행 없음");
    }

    /**
     * 꼬리말: 업로드 때 점역해 둔 값을 페이지행에 배치 (V31).
     * 종전엔 내려받을 때마다 AI를 다시 불렀는데, 이제 FooterBrailleService가 그 값을 돌려준다 —
     * 화면(페이지 조회·SSE)과 파일이 같은 값을 쓰게 하려는 것이다.
     */
    @Test
    void 꼬리말은_점역된_값을_페이지행에_넣는다() {
        givenJob("c", true, "수특 사회문화 2");
        PageResult p1 = pr(1, "c");
        when(pageResultRepo.findByJobIdOrderByPageNumber("job1")).thenReturn(List.of(p1));
        when(brailleRepo.findByPageResult(p1)).thenReturn(List.of(
                BrailleElement.builder().elementId("b1").content(List.of("⠼⠁⠃")).build()));
        when(footerBrailleService.resolve(job)).thenReturn("⠎⠣⠞⠕⠛");

        JobDownloadService.DownloadFile f = service.download(USER, "job1", null);

        verify(grpc, never()).translateText(anyString());   // 다운로드가 AI를 다시 부르지 않는다
        String[] lines = new String(f.content(), StandardCharsets.UTF_8).split("\n", -1);
        assertEquals(26, lines.length);
        String pageRow = lines[25];
        assertTrue(pageRow.contains("s"), "꼬리말 점자(⠎=s)가 페이지행에 배치됨: " + pageRow);
        assertTrue(pageRow.trim().endsWith("#a"), "점자 면 번호 1 오른쪽 정렬: " + pageRow);
    }

    /** 변환 중이면 JOB4010, 실패(결과 없음)면 JOB4012 */
    @Test
    void 변환_중이거나_결과_없으면_거절() {
        givenJob("a", false, null); // status COMPLETED → PENDING으로 되돌림
        job.updateStatus("IN_PROGRESS");
        assertEquals(ErrorCode.JOB_IN_PROGRESS,
                assertThrows(CustomException.class, () -> service.download(USER, "job1", null)).getErrorCode());

        job.updateStatus("FAILED");
        assertEquals(ErrorCode.JOB_NO_RESULT,
                assertThrows(CustomException.class, () -> service.download(USER, "job1", null)).getErrorCode());

        job.updateStatus("COMPLETED");
        when(pageResultRepo.findByJobIdOrderByPageNumber("job1")).thenReturn(List.of());
        assertEquals(ErrorCode.JOB_NO_RESULT,
                assertThrows(CustomException.class, () -> service.download(USER, "job1", null)).getErrorCode(),
                "COMPLETED인데 결과 행이 없으면(비정상 데이터) 방어");
    }

    /** 파일명: 요청값 우선, 확장자는 모드가 결정, 경로 문자는 치환 */
    @Test
    void 파일명은_요청값_우선_경로문자_제거() {
        givenJob("a", false, null);
        PageResult p1 = pr(1, "a");
        when(pageResultRepo.findByJobIdOrderByPageNumber("job1")).thenReturn(List.of(p1));
        when(textRepo.findByPageResult(p1)).thenReturn(List.of(
                TextElement.builder().elementId("e1").contents(List.of("본문")).build()));

        assertEquals("내 이름_지정.txt",
                service.download(USER, "job1", "내 이름/지정.pdf").fileName(), "요청값 우선 + 경로 문자 치환 + 확장자 교체");
    }
}
