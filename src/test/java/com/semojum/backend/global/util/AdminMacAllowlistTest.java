package com.semojum.backend.global.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminMacAllowlistTest {

    @Test
    void 대소문자와_하이픈_콜론_표기_편차를_흡수한다() {
        AdminMacAllowlist allowlist = new AdminMacAllowlist("D4-E9-8A-57-88-57, 50:f2:65:f0:99:6a");

        assertTrue(allowlist.isAllowed("d4:e9:8a:57:88:57"));
        assertTrue(allowlist.isAllowed("D4-E9-8A-57-88-57"));
        assertTrue(allowlist.isAllowed("50:F2:65:F0:99:6A"));
        assertFalse(allowlist.isAllowed("aa:bb:cc:dd:ee:ff"));
    }

    @Test
    void 미설정이거나_헤더가_없으면_거부한다() {
        AdminMacAllowlist empty = new AdminMacAllowlist("");
        assertFalse(empty.isAllowed("d4:e9:8a:57:88:57"));   // fail-safe: 빈 목록 = 전면 거부
        assertFalse(empty.isAllowed(null));
        assertFalse(empty.isAllowed(""));

        AdminMacAllowlist configured = new AdminMacAllowlist("d4:e9:8a:57:88:57");
        assertFalse(configured.isAllowed(null));
        assertFalse(configured.isAllowed("  "));
    }
}
