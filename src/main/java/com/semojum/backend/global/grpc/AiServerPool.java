package com.semojum.backend.global.grpc;

import com.semojum.backend.grpc.BrailleServiceGrpc;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 서버 풀 — 서버별 gRPC 채널과 동시 요청 슬롯(Semaphore)을 관리한다.
 *
 * 서버당 슬롯 수 = AI 서버가 동시에 수용 가능한 요청 수(내부 파이프라인 단계 수).
 * 슬롯을 항상 채워두면 AI 서버의 1단계 모델(OCR 등)이 쉬지 않고 다음 페이지를 이어받는다.
 * BE는 AI 내부 단계를 추적하지 않는다 — "서버당 동시 K건 유지"가 파이프라이닝의 전부다.
 *
 * 설정: grpc.ai.servers = host:port:슬롯수[,host:port:슬롯수...]
 * 서버 증설 시 환경변수(GRPC_AI_SERVERS)에 주소만 덧붙이면 된다(재배포 불필요, 재시작만).
 */
@Slf4j
@Component
public class AiServerPool {

    private final String serversConfig;
    private final String certPath;
    private final String authority;
    @Getter
    private final long deadlineSeconds;
    private final ResourceLoader resourceLoader;

    private final List<AiServer> servers = new ArrayList<>();
    @Getter
    private int totalSlots;

    public AiServerPool(
            @Value("${grpc.ai.servers}") String serversConfig,
            @Value("${grpc.ai.cert-path}") String certPath,
            @Value("${grpc.ai.authority}") String authority,
            @Value("${grpc.ai.deadline-seconds}") long deadlineSeconds,
            ResourceLoader resourceLoader) {
        this.serversConfig = serversConfig;
        this.certPath = certPath;
        this.authority = authority;
        this.deadlineSeconds = deadlineSeconds;
        this.resourceLoader = resourceLoader;
    }

    /** 파싱 결과 — 채널 생성과 분리해 단위 테스트 가능하게 둔다 */
    record ServerSpec(String host, int port, int slots) {}

    static List<ServerSpec> parse(String config) {
        List<ServerSpec> specs = new ArrayList<>();
        for (String entry : config.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException(
                        "grpc.ai.servers 형식 오류: '" + entry + "' (host:port:슬롯수 형식이어야 함)");
            }
            int port = Integer.parseInt(parts[1]);
            int slots = Integer.parseInt(parts[2]);
            if (slots < 1) {
                throw new IllegalArgumentException("슬롯 수는 1 이상이어야 함: '" + entry + "'");
            }
            specs.add(new ServerSpec(parts[0], port, slots));
        }
        return specs;
    }

    @PostConstruct
    public void init() throws IOException {
        ChannelCredentials credentials;
        try (InputStream certStream = resourceLoader.getResource(certPath).getInputStream()) {
            credentials = TlsChannelCredentials.newBuilder().trustManager(certStream).build();
        }

        for (ServerSpec spec : parse(serversConfig)) {
            ManagedChannel channel = Grpc.newChannelBuilderForAddress(spec.host(), spec.port(), credentials)
                    .overrideAuthority(authority)
                    .build();
            servers.add(new AiServer(spec.host() + ":" + spec.port(), channel, spec.slots()));
            totalSlots += spec.slots();
        }
        log.info("AiServerPool 초기화: 서버 {}대, 총 슬롯 {}개 ({})",
                servers.size(), totalSlots, serversConfig);
    }

    @PreDestroy
    public void shutdown() {
        for (AiServer server : servers) {
            server.channel.shutdown();
        }
        for (AiServer server : servers) {
            try {
                if (!server.channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 빈 슬롯이 있는 서버 중 진행 중 요청(inflight)이 가장 적은 서버를 선점한다.
     * 워커 수 == 총 슬롯 수라서 워커가 슬롯 없이 대기하는 일은 정상 상황에선 없지만,
     * 경합 순간을 대비해 짧은 대기 루프를 둔다.
     */
    public AiServer acquire() throws InterruptedException {
        while (true) {
            AiServer best = null;
            for (AiServer server : servers) {
                if (server.semaphore.availablePermits() > 0
                        && (best == null || server.inflight.get() < best.inflight.get())) {
                    best = server;
                }
            }
            if (best != null && best.semaphore.tryAcquire()) {
                best.inflight.incrementAndGet();
                return best;
            }
            Thread.sleep(50);
        }
    }

    public void release(AiServer server) {
        server.inflight.decrementAndGet();
        server.semaphore.release();
    }

    public static class AiServer {
        @Getter
        private final String name;
        private final ManagedChannel channel;
        private final Semaphore semaphore;
        private final AtomicInteger inflight = new AtomicInteger();
        @Getter
        private final BrailleServiceGrpc.BrailleServiceBlockingStub stub;

        AiServer(String name, ManagedChannel channel, int slots) {
            this.name = name;
            this.channel = channel;
            this.semaphore = new Semaphore(slots);
            this.stub = BrailleServiceGrpc.newBlockingStub(channel);
        }
    }
}
