package com.semojum.backend.global.grpc;

import com.semojum.backend.grpc.BrailleRequest;
import com.semojum.backend.grpc.BrailleResponse;
import com.semojum.backend.grpc.BrailleServiceGrpc;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 소켓 + TLS로 뜨는 모의 AI gRPC 서버로 병렬 디스패치를 검증한다.
 * 운영과 동일하게 자체 서명 인증서(SAN semo-jum.com) + authority-override 경로를 태운다.
 */
class AiServerPoolIntegrationTest {

    private static final String CERT = "classpath:grpc/test-server.crt";
    private static final String KEY = "classpath:grpc/test-server.key";
    private static final int SLOW_PAGE_NO = 99; // 이 페이지 번호는 모의 서버가 3초 걸리는 척한다

    private final List<Server> servers = new ArrayList<>();
    private AiServerPool pool;

    /** 동시 처리 수를 기록하는 모의 AI 서버 구현 */
    static class MockBrailleService extends BrailleServiceGrpc.BrailleServiceImplBase {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final AtomicInteger served = new AtomicInteger();
        final long sleepMillis;

        MockBrailleService(long sleepMillis) {
            this.sleepMillis = sleepMillis;
        }

        @Override
        public void processPage(BrailleRequest request, StreamObserver<BrailleResponse> observer) {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(request.getPageNo() == SLOW_PAGE_NO ? 3000 : sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            served.incrementAndGet();
            observer.onNext(BrailleResponse.newBuilder()
                    .setJobId(request.getJobId())
                    .setPageNumber(request.getPageNo())
                    .setStatus("COMPLETED")
                    .build());
            observer.onCompleted();
        }
    }

    private int startServer(MockBrailleService service) throws Exception {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        ServerCredentials credentials;
        try (InputStream cert = loader.getResource(CERT).getInputStream();
             InputStream key = loader.getResource(KEY).getInputStream()) {
            credentials = TlsServerCredentials.newBuilder().keyManager(cert, key).build();
        }
        Server server = Grpc.newServerBuilderForPort(0, credentials).addService(service).build().start();
        servers.add(server);
        return server.getPort();
    }

    private BrailleGrpcClient newClient(String serversConfig, long deadlineSeconds) throws Exception {
        pool = new AiServerPool(serversConfig, CERT, "semo-jum.com", deadlineSeconds, new DefaultResourceLoader());
        pool.init();
        return new BrailleGrpcClient(pool);
    }

    private static BrailleRequest request(int pageNo) {
        return BrailleRequest.newBuilder().setJobId("test-job").setPageNo(pageNo).setMode("a").build();
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.shutdown();
        servers.forEach(Server::shutdownNow);
        servers.clear();
    }

    @Test
    void 슬롯2_파이프라이닝_동시2건_유지되고_상한을_넘지_않는다() throws Exception {
        MockBrailleService mock = new MockBrailleService(400);
        int port = startServer(mock);
        BrailleGrpcClient client = newClient("localhost:" + port + ":2", 400);

        // 페이지 4장을 동시 요청 → 슬롯 2라 항상 2건씩 겹쳐서 처리돼야 한다
        ExecutorService executor = Executors.newFixedThreadPool(4);
        long start = System.currentTimeMillis();
        List<Future<BrailleResponse>> futures = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            final int pageNo = i;
            futures.add(executor.submit(() -> client.processPage(request(pageNo))));
        }
        for (Future<BrailleResponse> f : futures) {
            assertEquals("COMPLETED", f.get(10, TimeUnit.SECONDS).getStatus());
        }
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();

        assertEquals(4, mock.served.get());
        // 동시 2건이 실제로 겹쳤고(파이프라이닝 동작), 슬롯 상한 2를 넘지 않았다
        assertEquals(2, mock.maxActive.get());
        // 직렬이면 4×400=1600ms, 2병렬이면 ~800ms — 병렬 동작의 시간 증거
        assertTrue(elapsed < 1400, "2병렬이면 1400ms 미만이어야 함, 실제: " + elapsed + "ms");
    }

    @Test
    void 서버2대_라우팅_여유있는_서버로_균등_분배된다() throws Exception {
        MockBrailleService mock1 = new MockBrailleService(500);
        MockBrailleService mock2 = new MockBrailleService(500);
        int port1 = startServer(mock1);
        int port2 = startServer(mock2);
        BrailleGrpcClient client = newClient(
                "localhost:" + port1 + ":2,localhost:" + port2 + ":2", 400);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<BrailleResponse>> futures = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            final int pageNo = i;
            futures.add(executor.submit(() -> client.processPage(request(pageNo))));
        }
        for (Future<BrailleResponse> f : futures) {
            assertEquals("COMPLETED", f.get(10, TimeUnit.SECONDS).getStatus());
        }
        executor.shutdown();

        // 총 슬롯 4 = 2대×2 → 동시 4건이 두 서버에 2건씩 나뉘어야 한다
        assertEquals(2, mock1.served.get(), "서버1이 2건 처리해야 함");
        assertEquals(2, mock2.served.get(), "서버2가 2건 처리해야 함");
        assertEquals(2, mock1.maxActive.get());
        assertEquals(2, mock2.maxActive.get());
    }

    @Test
    void deadline_초과시_예외가_나고_슬롯은_반납된다() throws Exception {
        MockBrailleService mock = new MockBrailleService(200);
        int port = startServer(mock);
        // 슬롯 1개 + deadline 1초, 모의 서버는 SLOW_PAGE_NO에 3초 소요
        BrailleGrpcClient client = newClient("localhost:" + port + ":1", 1);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> client.processPage(request(SLOW_PAGE_NO)));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, ex.getStatus().getCode());

        // 유일한 슬롯이 반납됐다면 다음 요청이 즉시 처리된다 (반납 안 됐으면 여기서 무한 대기 → 테스트 타임아웃)
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BrailleResponse> next = executor.submit(() -> client.processPage(request(1)));
        assertEquals("COMPLETED", next.get(5, TimeUnit.SECONDS).getStatus());
        executor.shutdown();
    }

    @Test
    void 총슬롯수_계산() throws Exception {
        MockBrailleService mock = new MockBrailleService(100);
        int port = startServer(mock);
        newClient("localhost:" + port + ":2,localhost:" + port + ":1", 400);

        assertEquals(3, pool.getTotalSlots());
    }
}
