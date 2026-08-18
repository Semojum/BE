package com.semojum.backend.domain.billing;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.billing.entity.Coupon;
import com.semojum.backend.domain.billing.entity.CreditTransaction;
import com.semojum.backend.domain.billing.repository.CouponRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.billing.service.CreditDeductionService;
import com.semojum.backend.domain.org.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// "쿠폰부터 차감하고 소진되면 계약 크레딧에서" (기획 확정) — 출처 판정 규칙
class CreditDeductionServiceTest {

    private CouponRepository couponRepository;
    private CreditTransactionRepository txRepository;
    private CreditDeductionService service;
    private User user;
    private Coupon coupon;

    private static void setId(Object entity, Object value) throws Exception {
        var f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        couponRepository = Mockito.mock(CouponRepository.class);
        txRepository = Mockito.mock(CreditTransactionRepository.class);
        service = new CreditDeductionService(couponRepository, txRepository);

        Organization org = Organization.builder().name("기관A").code("orga").build();
        setId(org, UUID.randomUUID());
        user = User.builder().loginId("orga01").organization(org).password("pw").build();
        setId(user, UUID.randomUUID());

        coupon = Coupon.builder().organizationId(org.getId()).name("PoC 체험")
                .creditAmount(10).startsOn(LocalDate.now().minusDays(1)).endsOn(LocalDate.now().plusDays(1)).build();
        setId(coupon, UUID.randomUUID());
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreditTransaction captureDeduct(int credit) {
        service.deduct(user, "job_x", 1, "PAGE_LAYOUT_TABLE", credit);
        ArgumentCaptor<CreditTransaction> captor = ArgumentCaptor.forClass(CreditTransaction.class);
        Mockito.verify(txRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 잔량_있는_쿠폰이_먼저() {
        when(couponRepository.findActiveForUpdate(any(), any())).thenReturn(List.of(coupon));
        when(txRepository.sumByCoupon(coupon.getId())).thenReturn(7L);   // 잔량 3 ≥ 차감 3

        CreditTransaction tx = captureDeduct(3);
        assertEquals("COUPON", tx.getSource());
        assertEquals(coupon.getId(), tx.getCouponId());
    }

    @Test
    void 잔량_부족_쿠폰은_건너뛰고_계약에서() {
        when(couponRepository.findActiveForUpdate(any(), any())).thenReturn(List.of(coupon));
        when(txRepository.sumByCoupon(coupon.getId())).thenReturn(8L);   // 잔량 2 < 차감 3

        CreditTransaction tx = captureDeduct(3);
        assertEquals("CONTRACT", tx.getSource());
        assertNull(tx.getCouponId());
    }

    @Test
    void 쿠폰_없으면_계약에서_그리고_0차감은_쿠폰_판정_생략() {
        when(couponRepository.findActiveForUpdate(any(), any())).thenReturn(List.of());
        assertEquals("CONTRACT", captureDeduct(5).getSource());

        Mockito.reset(txRepository);
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreditTransaction zero = captureDeduct(0);   // UNSPECIFIED — 0 차감도 기록, 쿠폰 소진엔 무관
        assertEquals("CONTRACT", zero.getSource());
        assertEquals(0, zero.getAmount());
    }
}
