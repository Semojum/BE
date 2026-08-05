package com.semojum.backend;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 애플리케이션 시간대가 한국 표준시로 고정되는지 검증한다.
 *
 * <p>컨테이너가 UTC로 돌던 시기에 카드 날짜가 9시간 어긋난 적이 있어,
 * 환경변수와 별개로 코드에서도 고정하고 그 동작을 회귀 테스트로 묶는다.
 */
class ApplicationTimeZoneTest {

    @Test
    void 기동_시_기본_시간대가_서울로_고정된다() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));   // 배포 환경이 UTC인 상황 재현
            new BackendApplication().setDefaultTimeZone();
            assertEquals(ZoneId.of("Asia/Seoul"), TimeZone.getDefault().toZoneId());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void 서울_기준_now가_UTC보다_9시간_앞선다() {
        TimeZone original = TimeZone.getDefault();
        try {
            new BackendApplication().setDefaultTimeZone();
            // 한 시점을 두 시간대로 해석해 비교한다(now()를 두 번 부르면 미세한 시차로 잘림 오차 발생)
            Instant moment = Instant.now();
            LocalDateTime seoul = LocalDateTime.ofInstant(moment, ZoneId.systemDefault());
            LocalDateTime utc = LocalDateTime.ofInstant(moment, ZoneId.of("UTC"));
            assertEquals(9, Duration.between(utc, seoul).toHours(), "KST는 UTC+9여야 한다");
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
