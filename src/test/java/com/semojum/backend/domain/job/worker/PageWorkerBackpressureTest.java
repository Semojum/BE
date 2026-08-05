package com.semojum.backend.domain.job.worker;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AI 백프레셔(RESOURCE_EXHAUSTED) 분류 검증 — 재시도 3회를 소모하지 않는 신호 구분 */
class PageWorkerBackpressureTest {

    @Test
    void RESOURCE_EXHAUSTED는_백프레셔() {
        assertTrue(PageWorker.isBackpressure(
                new StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription("Concurrent RPC limit exceeded!"))));
    }

    @Test
    void 다른_gRPC_에러는_백프레셔_아님() {
        assertFalse(PageWorker.isBackpressure(new StatusRuntimeException(Status.DEADLINE_EXCEEDED)));
        assertFalse(PageWorker.isBackpressure(new StatusRuntimeException(Status.UNAVAILABLE)));
    }

    @Test
    void 일반_예외는_백프레셔_아님() {
        assertFalse(PageWorker.isBackpressure(new RuntimeException("S3 다운로드 실패")));
    }
}
