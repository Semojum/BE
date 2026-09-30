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
    /**
     * 여러 문장을 슬롯 하나로 연달아 점역한다 — 편집 저장의 재점역용(3패널, 2026-09-30).
     * 문장마다 슬롯을 잡으면 변환이 몰릴 때 문장 수만큼 대기가 쌓인다.
     *
     * @param maxWaitMs 슬롯 대기 상한. 넘기면 {@link AiBusyException}
     * @return 입력과 같은 순서의 점자
     */
    public java.util.List<String> translateTexts(java.util.List<String> texts, long maxWaitMs) {
        if (texts.isEmpty()) return java.util.List.of();
        AiServerPool.AiServer server;
        try {
            server = pool.tryAcquire(maxWaitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 서버 슬롯 대기 중 인터럽트", e);
        }
        if (server == null) throw new AiBusyException();
        try {
            java.util.List<String> out = new java.util.ArrayList<>(texts.size());
            for (String text : texts) {
                TranslateTextReply reply = server.getStub()
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .translateText(TranslateTextRequest.newBuilder().setText(text).build());
                out.add(reply.getBraille());
            }
            return out;
        } finally {
            pool.release(server);
        }
    }

    /** AI 슬롯이 전부 변환에 쓰이고 있어 제한 시간 안에 못 잡음 */
    public static class AiBusyException extends RuntimeException {
        public AiBusyException() {
            super("AI 서버 슬롯 대기 시간 초과");
        }
    }
}
