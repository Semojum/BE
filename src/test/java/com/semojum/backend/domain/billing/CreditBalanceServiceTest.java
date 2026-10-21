package com.semojum.backend.domain.billing;

import com.semojum.backend.domain.billing.repository.CouponRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.billing.service.CreditBalanceService;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 잔여 크레딧 = 계약 배정 − 계약 사용 + 유효 쿠폰 남은 양 */
class CreditBalanceServiceTest {

    private final UUID org = UUID.randomUUID();
    private OrganizationRepository orgs;
    private CreditTransactionRepository txs;
    private CouponRepository coupons;
    private CreditBalanceService service;

    @BeforeEach
    void setUp() {
        orgs = mock(OrganizationRepository.class);
        txs = mock(CreditTransactionRepository.class);
        coupons = mock(CouponRepository.class);
        service = new CreditBalanceService(orgs, txs, coupons);
        when(orgs.findCreditAllocated(org)).thenReturn(1000L);
        when(txs.sumContractByOrganization(org)).thenReturn(261L);
    }

    @Test
    void 계약_잔여에_유효_쿠폰_남은_양을_더한다() {
        when(coupons.sumActiveAmount(eq(org), any())).thenReturn(100L);
        when(txs.sumUsedOnActiveCoupons(eq(org), any())).thenReturn(30L);
        assertEquals(1000 - 261 + 70, service.remaining(org));
    }

    /** 배정 0인 기관이 계약에서 써 왔으면 마이너스다 — 변환이 막혀야 한다 */
    @Test
    void 계약은_마이너스가_될_수_있다() {
        when(orgs.findCreditAllocated(org)).thenReturn(0L);
        when(txs.sumContractByOrganization(org)).thenReturn(104L);
        assertEquals(-104, service.remaining(org));
    }

    @Test
    void 잠깐_기억하고_차감되면_버린다() {
        service.remaining(org);
        service.remaining(org);
        verify(txs, times(1)).sumContractByOrganization(org);

        service.evict(org);   // 트랜잭션 밖 — 바로 버린다
        when(txs.sumContractByOrganization(org)).thenReturn(266L);
        assertEquals(1000 - 266, service.remaining(org));
    }
}
