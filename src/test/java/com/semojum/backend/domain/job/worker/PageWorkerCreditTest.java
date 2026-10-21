package com.semojum.backend.domain.job.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.scheduler.CreditGate;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.domain.job.service.JobCancelService;
import com.semojum.backend.domain.job.service.PageImageService;
import com.semojum.backend.domain.result.service.ResultService;
import com.semojum.backend.global.grpc.AiServerPool;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 기관 잔여 크레딧이 0 이하면 AI에 보내지 않고 「크레딧 부족」으로 막는다 (2026-10-21).
 * 워커 루프를 실제로 한 바퀴 돌린다.
 */
class PageWorkerCreditTest {

    private static final String TASK =
            "{\"jobId\":\"j1\",\"pageNo\":3,\"userId\":\"u\",\"mode\":\"c\",\"totalPages\":5,\"sourcePath\":\"jobs/j1/p3.pdf\"}";

    private JobDispatcher dispatcher;
    private CreditGate creditGate;
    private ResultService resultService;
    private BrailleGrpcClient grpc;
    private S3Service s3;
    private HashOperations<String, Object, Object> hash;
    private PageWorker worker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn((HashOperations) hash);
        dispatcher = mock(JobDispatcher.class);
        when(dispatcher.poll()).thenReturn(TASK).thenReturn(null);
        creditGate = mock(CreditGate.class);
        resultService = mock(ResultService.class);
        grpc = mock(BrailleGrpcClient.class);
        s3 = mock(S3Service.class);
        AiServerPool pool = mock(AiServerPool.class);
        when(pool.getTotalSlots()).thenReturn(1);

        worker = new PageWorker(redis, s3, grpc, new ObjectMapper(), resultService, pool, dispatcher,
                mock(JobCancelService.class), mock(PageImageService.class), creditGate);
    }

    @AfterEach
    void tearDown() {
        worker.stopWorkers();
    }

    @Test
    void 잔여가_없으면_AI에_보내지_않고_크레딧_부족으로_막는다() {
        when(creditGate.exhausted("j1")).thenReturn(true);
        worker.startWorkers();

        verify(resultService, timeout(3000)).markPageBlocked("j1", 3, Page.BLOCKED_CREDIT_EXHAUSTED);
        verify(hash, timeout(3000)).put("job:j1:pages", "page:3", "BLOCKED");
        verify(creditGate, timeout(3000)).release(TASK);   // 관문의 「AI에 있는 쪽」에서 빠진다
        verifyNoInteractions(grpc, s3);
    }

    @Test
    void 잔여가_있으면_AI에_보낸다() throws Exception {
        when(creditGate.exhausted("j1")).thenReturn(false);
        when(s3.downloadFile(any())).thenReturn(new byte[]{1});
        worker.startWorkers();

        verify(grpc, timeout(3000)).processPage(any());
        verify(resultService, never()).markPageBlocked(any(), anyInt(), any());
    }
}
