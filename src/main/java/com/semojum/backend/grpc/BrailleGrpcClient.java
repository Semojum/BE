package com.semojum.backend.grpc;

import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BrailleGrpcClient {

    @GrpcClient("ai-server")
    private BrailleServiceGrpc.BrailleServiceBlockingStub stub;

    public BrailleResponse processPage(BrailleRequest request) {
        return stub.processPage(request);
    }
}