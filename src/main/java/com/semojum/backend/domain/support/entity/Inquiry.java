package com.semojum.backend.domain.support.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 문의 — T2 기관 관리자의 요청(크레딧 추가·계정 발급)과 오류 신고 등이 T1-9 목록으로 모인다.
 * 상태: OPEN(미답변) → IN_REVIEW(확인 중) → ANSWERED(답변 완료). 취소는 OPEN에서 hard delete.
 * 미가입(홈페이지) 문의 유입 경로는 후속 — organizationId/userId가 null인 행이 그 자리.
 */
@Entity
@Table(name = "inquiries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry {

    public static final String TYPE_CREDIT_ADD = "CREDIT_ADD";
    public static final String TYPE_ACCOUNT_ISSUE = "ACCOUNT_ISSUE";
    public static final String TYPE_ERROR_REPORT = "ERROR_REPORT";
    public static final String TYPE_ONBOARDING = "ONBOARDING";
    public static final String TYPE_EMAIL = "EMAIL";     // 회사 메일함 수신 (MailInboxPoller)
    public static final String TYPE_ETC = "ETC";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_IN_REVIEW = "IN_REVIEW";
    public static final String STATUS_ANSWERED = "ANSWERED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "organization_id", columnDefinition = "uuid")
    private UUID organizationId;

    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(columnDefinition = "text")
    private String message;

    // 메일 문의(TYPE_EMAIL) 전용 — 보낸 사람 자리에 메일 주소를 표시한다
    @Column(name = "sender_email", length = 100)
    private String senderEmail;

    @Column(length = 300)
    private String subject;

    // "UIDVALIDITY:UID" — 폴링 재수집 시 중복 방지 (DB 유니크)
    @Column(name = "mail_uid", length = 60)
    private String mailUid;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Builder
    public Inquiry(String type, UUID organizationId, UUID userId, String message,
                   String senderEmail, String subject, String mailUid) {
        this.type = type;
        this.status = STATUS_OPEN;
        this.organizationId = organizationId;
        this.userId = userId;
        this.message = message;
        this.senderEmail = senderEmail;
        this.subject = subject;
        this.mailUid = mailUid;
    }

    public void changeStatus(String status) {
        this.status = status;
        this.statusChangedAt = Instant.now();
    }
}
