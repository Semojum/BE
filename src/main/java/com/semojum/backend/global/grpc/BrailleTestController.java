package com.semojum.backend.global.grpc;

import com.google.protobuf.ByteString;
import com.semojum.backend.grpc.BrailleRequest;
import com.semojum.backend.grpc.BrailleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class BrailleTestController {

    private final BrailleGrpcClient brailleGrpcClient;

    @GetMapping("/grpc")
    public String testGrpc() {
        BrailleRequest request = BrailleRequest.newBuilder()
                .setJobId("test-job-001")
                .setPageNo(1)
                .setTotalPages(1)
                .setPdfData(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .setMode("a")
                .setSourceText("")
                .build();

        BrailleResponse response = brailleGrpcClient.processPage(request);

        return "status: " + response.getStatus()
                + ", job_id: " + response.getJobId()
                + ", page: " + response.getPageNumber();
    }
}