package com.semojum.backend.global.grpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiServerPoolTest {

    @Test
    void 단일_서버_파싱() {
        List<AiServerPool.ServerSpec> specs = AiServerPool.parse("172.31.47.101:50051:2");

        assertEquals(1, specs.size());
        assertEquals("172.31.47.101", specs.get(0).host());
        assertEquals(50051, specs.get(0).port());
        assertEquals(2, specs.get(0).slots());
    }

    @Test
    void 복수_서버_파싱_공백_허용() {
        List<AiServerPool.ServerSpec> specs = AiServerPool.parse("ip1:50051:2, ip2:50052:1");

        assertEquals(2, specs.size());
        assertEquals("ip2", specs.get(1).host());
        assertEquals(50052, specs.get(1).port());
        assertEquals(1, specs.get(1).slots());
        assertEquals(3, specs.stream().mapToInt(AiServerPool.ServerSpec::slots).sum());
    }

    @Test
    void 슬롯수_누락이면_예외() {
        assertThrows(IllegalArgumentException.class, () -> AiServerPool.parse("ip1:50051"));
    }

    @Test
    void 슬롯수_0이면_예외() {
        assertThrows(IllegalArgumentException.class, () -> AiServerPool.parse("ip1:50051:0"));
    }
}
