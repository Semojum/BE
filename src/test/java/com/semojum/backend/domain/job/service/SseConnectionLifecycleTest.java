package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SSE 연결 관리 — 하트비트와 "이미 끝난 작업" 즉시 종료 (2026-09-19).
 *
 * <p>해결하는 두 가지:
 * <ol>
 *   <li><b>죽은 연결을 못 잡는다</b> — 보낼 이벤트가 없는 구간(마지막 쪽들이 전부 AI 서버에
 *       들어가 있을 때, 최대 gRPC deadline 400초)에는 전송이 아예 없어 클라이언트가 사라져도
 *       알 수 없었다. 주기적으로 주석 줄을 내보내면 그때 IOException으로 드러난다</li>
 *   <li><b>끝난 작업에 붙으면 3시간 매달린다</b> — 상태 Hash는 종료 후 TTL 1시간으로 사라지는데,
 *       "아직 기록 전"과 구분되지 않아 무한히 기다렸다. 하트비트로는 안 잡힌다(전송이 정상
 *       성공하므로). 작업 상태가 DB에 남아 있으므로 그것으로 판정한다</li>
 * </ol>
 */
class SseConnectionLifecycleTest {

    JobRepository jobRepository;
    SseService sseService;
    SseEmitter emitter;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        emitter = Mockito.mock(SseEmitter.class);
        sseService = new SseService(jobRepository, null, null, null, null, null, null, null, null);
    }

    private Job job(String status, int totalPages, int[] failedPages) {
        Job job = Job.builder()
                .id("job-1").user(User.builder().loginId("testorg01").password("pw").build())
                .mode("a").totalPages(totalPages).originalFileName("교재.pdf")
                .build();
        if ("COMPLETED".equals(status)) job.complete(failedPages);
        else job.updateStatus(status);
        return job;
    }

    // ── 하트비트 ────────────────────────────────────────────────────────

    @Test
    void 간격이_지나면_주석_줄을_보낸다() throws IOException {
        long start = 1_000_000L;

        long next = sseService.maybeHeartbeat(emitter, start, start + SseService.HEARTBEAT_INTERVAL_MS);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(start + SseService.HEARTBEAT_INTERVAL_MS, next, "보냈으면 기준 시각이 now로 밀린다");
    }

    /** 이벤트를 방금 보냈으면 하트비트는 쉰다 — 타이머가 전송 때마다 초기화된다는 뜻 */
    @Test
    void 간격_전에는_보내지_않는다() throws IOException {
        long start = 1_000_000L;

        long next = sseService.maybeHeartbeat(emitter, start, start + SseService.HEARTBEAT_INTERVAL_MS - 1);

        verifyNoInteractions(emitter);
        assertEquals(start, next, "안 보냈으면 기준 시각이 그대로여야 다음에 제때 나간다");
    }

    /** 죽은 연결은 이 전송에서 드러난다 — 루프의 catch(IOException)가 받아 정리한다 */
    @Test
    void 연결이_죽어_있으면_IOException이_올라온다() throws IOException {
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThrows(IOException.class,
                () -> sseService.maybeHeartbeat(emitter, 0L, SseService.HEARTBEAT_INTERVAL_MS));
    }

    // ── 끝난 작업 즉시 종료 ──────────────────────────────────────────────

    @Test
    void 완료된_작업이면_job_done_페이로드를_준다() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job("COMPLETED", 5, new int[]{2, 4})));

        Map<String, Object> payload = sseService.terminalJobDonePayload("job-1");

        assertNotNull(payload);
        assertEquals("job_done", payload.get("type"));
        assertEquals("job-1", payload.get("job_id"));
        assertEquals(5, payload.get("total_pages"));
        assertEquals(List.of(2, 4), payload.get("failed_pages"));
    }

    @Test
    void 실패한_작업도_종료로_본다() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job("FAILED", 3, null)));

        assertNotNull(sseService.terminalJobDonePayload("job-1"), "FAILED도 더 기다릴 이유가 없다");
    }

    /** 아직 기록 전인 작업 — 여기서 끝내면 변환 시작 전에 화면이 닫힌다 */
    @Test
    void 진행_중이면_종료하지_않는다() {
        for (String status : new String[]{"PENDING", "IN_PROGRESS"}) {
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job(status, 3, null)));

            assertNull(sseService.terminalJobDonePayload("job-1"), status);
        }
    }

    @Test
    void 작업이_DB에도_없으면_종료하지_않는다() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.empty());

        assertNull(sseService.terminalJobDonePayload("job-1"));
    }

    // ── 두 경로의 job_done 모양이 같아야 한다 ────────────────────────────

    /**
     * 정상 종료와 "이미 끝난 작업"이 같은 모양을 내보내야 FE가 둘을 구분하지 않는다.
     * 키 순서까지 같은지 본다 — 한쪽만 바뀌면 여기서 잡힌다.
     */
    @Test
    void 두_경로가_같은_모양을_낸다() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job("COMPLETED", 5, new int[]{2, 4})));

        Map<String, Object> fromDb = sseService.terminalJobDonePayload("job-1");
        Map<String, Object> fromLoop = SseService.buildJobDonePayload("job-1", 5, new int[]{2, 4});

        assertEquals(List.copyOf(fromLoop.keySet()), List.copyOf(fromDb.keySet()));
        assertEquals(fromLoop, fromDb);
    }

    @Test
    void 실패_쪽이_없으면_빈_배열이다() {
        Map<String, Object> payload = SseService.buildJobDonePayload("job-1", 3, new int[]{});

        assertEquals(List.of(), payload.get("failed_pages"), "null이 아니라 빈 배열 — FE가 null 체크 없이 순회한다");
    }
}
