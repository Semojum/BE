-- V18: 문의·공지·주문 (관리자 대시보드 V11 — T1-7 수납·T1-9 문의·T1-10 공지, T2 조회·요청)

-- ── 공지 (운영자 작성 → 기관 관리자 화면 T2에 노출) ──
-- target_organization_id null = 전체 공지. 노출 기간이 지나면 자동 종료(조회 시 기간 필터 — 스케줄러 없음)
CREATE TABLE IF NOT EXISTS notices (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    target_organization_id UUID,
    title                  VARCHAR(200) NOT NULL,
    body                   TEXT         NOT NULL,
    starts_on              DATE         NOT NULL,
    ends_on                DATE         NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notices_period ON notices (starts_on, ends_on);

-- ── 문의 (T2 기관 관리자의 크레딧 추가·계정 발급 요청 + 오류 신고 등 — T1-9 목록으로 모임) ──
-- 취소는 OPEN 상태에서 hard delete (접수 전 회수). 경과 표시는 FE가 created_at으로 계산
CREATE TABLE IF NOT EXISTS inquiries (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    type              VARCHAR(30) NOT NULL,             -- CREDIT_ADD | ACCOUNT_ISSUE | ERROR_REPORT | ONBOARDING | ETC
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',  -- OPEN(미답변) | IN_REVIEW(확인 중) | ANSWERED(답변 완료)
    organization_id   UUID,                             -- 미가입(홈페이지) 문의는 null — 유입 경로는 후속
    user_id           UUID,
    message           TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    status_changed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_inquiries_status ON inquiries (status, created_at);
CREATE INDEX IF NOT EXISTS idx_inquiries_org    ON inquiries (organization_id, created_at);

-- ── 주문·수납 (운영자 수동 기록 — 결제 연동 없음. T1-7 기록, T2 조회) ──
CREATE TABLE IF NOT EXISTS orders (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    order_date      DATE         NOT NULL,
    description     VARCHAR(200) NOT NULL,              -- 예: "연간 계약 · 10,000 크레딧"
    amount_krw      BIGINT       NOT NULL,
    credit_amount   BIGINT,                             -- 이 주문의 크레딧 수량 (참고 — 할당 반영은 운영자가 별도)
    paid_at         DATE,                               -- null = 미납. 입금 확인 시 운영자가 기록
    invoice_status  VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING(발행 대기) | ISSUED(발행 완료)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_orders_org ON orders (organization_id, order_date);

-- 증빙(계산서) 받는 사람 — T2 주문 내역 하단
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS receipt_email VARCHAR(100);
