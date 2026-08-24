package com.semojum.backend.global.hwp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.FileInputStream;
import java.util.List;

// 일회성 프로브 — 현행 HwpPageExtractor 직렬화 출력을 그대로 덤프 (커밋 제외 대상)
@EnabledIfEnvironmentVariable(named = "HWP_DEBUG_FILE", matches = ".+")
class SerializeProbeTest {

    @Test
    void dump() throws Exception {
        List<String> pages = new HwpPageExtractor()
                .extractPages(new FileInputStream(System.getenv("HWP_DEBUG_FILE")));
        for (int i = 0; i < pages.size(); i++) {
            System.out.println("───── 페이지 " + (i + 1) + " ─────");
            System.out.println(pages.get(i));
        }
    }
}
