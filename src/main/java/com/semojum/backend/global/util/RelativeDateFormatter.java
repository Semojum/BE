package com.semojum.backend.global.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 마이페이지 카드에 표시할 날짜 문자열을 만든다.
 *
 * <p>피그마 V3-05 기준 — 오늘이면 경과 시간("1시간 전"), 어제면 "어제",
 * 올해면 월·일("7. 28."), 해가 다르면 연도까지("2025. 12. 3.").
 *
 * <p>서버 시간대(KST) 기준으로 계산해 내려준다. 클라이언트마다 다르게 계산해
 * 표시가 엇갈리는 것을 막고, 규칙이 바뀌어도 서버만 고치면 된다.
 */
public final class RelativeDateFormatter {

    private RelativeDateFormatter() {}

    public static String format(LocalDateTime target, LocalDateTime now) {
        if (target == null) return null;

        LocalDate targetDay = target.toLocalDate();
        LocalDate today = now.toLocalDate();

        if (targetDay.isEqual(today)) {
            long minutes = Duration.between(target, now).toMinutes();
            if (minutes < 1) return "방금";       // 음수(시계 오차로 미래)도 여기서 흡수
            if (minutes < 60) return minutes + "분 전";
            return Duration.between(target, now).toHours() + "시간 전";
        }
        if (targetDay.isEqual(today.minusDays(1))) {
            return "어제";
        }
        if (targetDay.getYear() == today.getYear()) {
            return targetDay.getMonthValue() + ". " + targetDay.getDayOfMonth() + ".";
        }
        return targetDay.getYear() + ". " + targetDay.getMonthValue() + ". " + targetDay.getDayOfMonth() + ".";
    }

    public static String format(LocalDateTime target) {
        return format(target, LocalDateTime.now());
    }
}
