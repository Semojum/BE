package com.semojum.backend.global.grpc;

import com.semojum.backend.grpc.BrailleRequest;
import com.semojum.backend.grpc.BrailleResponse;
import com.semojum.backend.grpc.TranslateTextReply;
import com.semojum.backend.grpc.TranslateTextRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrailleGrpcClient {

    private final AiServerPool pool;

    /**
     * 풀에서 슬롯을 확보한 서버로 변환 요청을 보낸다.
     * deadline(200s)은 AI 서버 하드 타임아웃(180s)보다 높게 — 응답 없는 요청이
     * 슬롯을 영구 점유하는 것을 막는다(deadline 초과 시 예외 → 슬롯 반납 → 재시도 로직으로).
     */
    public BrailleResponse processPage(BrailleRequest request) {
        AiServerPool.AiServer server;
        try {
            server = pool.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 서버 슬롯 대기 중 인터럽트", e);
        }
        try {
            return server.getStub()
                    .withDeadlineAfter(pool.getDeadlineSeconds(), TimeUnit.SECONDS)
                    .processPage(request);
        } finally {
            pool.release(server);
        }
    }

    /**
     * 꼬리말(책 이름 등) 짧은 묵자 → 점자 변환 (proto 08-05 신규 RPC).
     * AI 쪽이 rule-based 직행이라 수 ms에 끝나지만, 동시 수용 한도(RESOURCE_EXHAUSTED)를
     * 넘지 않도록 페이지 변환과 같은 슬롯 풀을 경유한다. 200자 초과는 AI가 거절.
     */
    public String translateText(String text) {
        AiServerPool.AiServer server;
        try {
            server = pool.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 서버 슬롯 대기 중 인터럽트", e);
        }
        try {
            TranslateTextReply reply = server.getStub()
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .translateText(TranslateTextRequest.newBuilder().setText(text).build());
            return reply.getBraille();
        } finally {
            pool.release(server);
        }
    }
}
