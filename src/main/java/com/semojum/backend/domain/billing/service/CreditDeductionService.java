package com.semojum.backend.domain.billing.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.billing.entity.Coupon;
import com.semojum.backend.domain.billing.entity.CreditTransaction;
import com.semojum.backend.domain.billing.repository.CouponRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 쪽 단위 크레딧 차감 — 출처 판정 포함 (기획 확정: "쿠폰부터 차감하고 소진되면 계약 크레딧에서").
 * 호출자(ResultService.save)의 트랜잭션에 참여한다.
 *
 * <p>쿠폰 선택: 유효 기간 내 쿠폰을 발급 오래된 순으로 보고, 이번 차감 전액이 들어갈 잔량이 있는
 * 첫 쿠폰을 쓴다(쪽 차감은 원자 단위 — 쿠폰·계약에 쪼개 담지 않는다, 잔량 부족 쿠폰은 건너뜀).
 * 워커 4개가 병렬 차감하므로 쿠폰 행을 잠그고(findActiveForUpdate) 잔량을 계산해 초과 소진을 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditDeductionService {

    public static final String SOURCE_CONTRACT = "CONTRACT";
    public static final String SOURCE_COUPON = "COUPON";

    private final CouponRepository couponRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    public void deduct(User user, String jobId, int pageNo, String layoutType, int credit) {
        UUID orgId = user.getOrganization() != null ? user.getOrganization().getId() : null;

        String source = SOURCE_CONTRACT;
        UUID couponId = null;
        if (credit > 0 && orgId != null) {
            for (Coupon coupon : couponRepository.findActiveForUpdate(orgId, LocalDate.now())) {
                long used = creditTransactionRepository.sumByCoupon(coupon.getId());
                if (used + credit <= coupon.getCreditAmount()) {
                    source = SOURCE_COUPON;
                    couponId = coupon.getId();
                    break;
                }
            }
        }

        creditTransactionRepository.save(CreditTransaction.builder()
                .userId(user.getId())
                .organizationId(orgId)
                .jobId(jobId)
                .pageNo(pageNo)
                .layoutType(layoutType)
                .amount(credit)
                .source(source)
                .couponId(couponId)
                .build());
        if (couponId != null) {
            log.info("쿠폰 차감: jobId={}, pageNo={}, couponId={}, amount={}", jobId, pageNo, couponId, credit);
        }
    }
}
