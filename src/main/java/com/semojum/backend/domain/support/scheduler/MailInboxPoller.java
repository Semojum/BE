package com.semojum.backend.domain.support.scheduler;

import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.entity.InquiryAttachment;
import com.semojum.backend.domain.support.repository.InquiryAttachmentRepository;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.global.s3.S3Service;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * T1-9 문의 메일 연동 — 회사 메일함(Google Workspace)을 IMAP으로 읽어 inquiries에 넣는다.
 * 새 메일 = type EMAIL, 보낸 사람 자리에 sender_email. 상태 관리(미답변→답변 완료)는 기존과 동일.
 *
 * <p>읽기 전용(READ_ONLY)으로 열어 메일함의 읽음 표시를 건드리지 않는다 — 답장은 메일함에서.
 * 자격증명(MAIL_INBOX_USERNAME/PASSWORD — Workspace 앱 비밀번호)이 없으면 폴러는 조용히 쉰다(fail-safe).
 * 중복 방지: "UIDVALIDITY:UID" 키 (existsByMailUid + DB 유니크가 최종 방어선).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailInboxPoller {

    private static final int FETCH_WINDOW = 50;        // 폴링마다 최근 N통만 검사
    private static final int BODY_MAX_CHARS = 10_000;
    // 첨부 보관 상한 — 초과분은 건너뛰고 로그만 (메일 자체는 저장)
    private static final int MAX_ATTACHMENTS = 10;
    private static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    private final InquiryRepository inquiryRepository;
    private final InquiryAttachmentRepository inquiryAttachmentRepository;
    private final S3Service s3Service;

    @Value("${mail.inbox.host:imap.gmail.com}")
    private String host;

    @Value("${mail.inbox.username:}")
    private String username;

    @Value("${mail.inbox.password:}")
    private String password;

    private boolean disabledLogged = false;

    @Scheduled(fixedDelayString = "${mail.inbox.poll-delay-ms:300000}", initialDelay = 30_000)
    public void poll() {
        if (username.isBlank() || password.isBlank()) {
            if (!disabledLogged) {
                log.info("문의 메일 연동 비활성 — MAIL_INBOX_USERNAME/PASSWORD 미설정");
                disabledLogged = true;
            }
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", host);
            props.put("mail.imaps.port", "993");
            props.put("mail.imaps.connectiontimeout", "10000");
            props.put("mail.imaps.timeout", "30000");

            Store store = Session.getInstance(props).getStore("imaps");
            store.connect(host, username, password);
            try (AutoCloseable ignored = store::close) {
                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                try {
                    UIDFolder uidFolder = (UIDFolder) inbox;
                    long uidValidity = uidFolder.getUIDValidity();
                    int count = inbox.getMessageCount();
                    if (count == 0) return;
                    int start = Math.max(1, count - FETCH_WINDOW + 1);
                    int saved = 0;
                    for (Message msg : inbox.getMessages(start, count)) {
                        String mailUid = uidValidity + ":" + uidFolder.getUID(msg);
                        if (inquiryRepository.existsByMailUid(mailUid)) continue;
                        try {
                            Inquiry inquiry = inquiryRepository.save(Inquiry.builder()
                                    .type(Inquiry.TYPE_EMAIL)
                                    .senderEmail(senderAddress(msg))
                                    .subject(truncate(msg.getSubject(), 300))
                                    .message(truncate(extractText(msg), BODY_MAX_CHARS))
                                    .mailUid(mailUid)
                                    .build());
                            saveAttachments(inquiry, msg);
                            saved++;
                        } catch (Exception e) {
                            // 개별 메일 실패(파싱·유니크 경합)는 다음 폴링에 재시도 — 전체를 막지 않는다
                            log.warn("메일 저장 실패(다음 폴링 재시도): uid={}, error={}", mailUid, e.getMessage());
                        }
                    }
                    if (saved > 0) log.info("문의 메일 수신: {}건 저장", saved);
                } finally {
                    inbox.close(false);
                }
            }
        } catch (Exception e) {
            log.warn("문의 메일함 폴링 실패(다음 주기 재시도): {}", e.getMessage());
        }
    }

    // 보낸 사람 — 표시용은 메일 주소만 (개인 이름 미저장)
    static String senderAddress(Message msg) throws Exception {
        var from = msg.getFrom();
        if (from == null || from.length == 0) return null;
        return from[0] instanceof InternetAddress ia ? ia.getAddress() : from[0].toString();
    }

    // 본문 — text/plain 우선, 없으면 text/html 원문(태그 포함이라도 내용 유실보다 낫다)
    static String extractText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) return String.valueOf(part.getContent());
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part child = mp.getBodyPart(i);
                if (child.isMimeType("text/plain")) return String.valueOf(child.getContent());
                if (child.isMimeType("text/html") && html == null) html = String.valueOf(child.getContent());
                if (child.isMimeType("multipart/*")) {
                    String nested = extractText(child);
                    if (nested != null) return nested;
                }
            }
            return html;
        }
        if (part.isMimeType("text/html")) return String.valueOf(part.getContent());
        return null;
    }

    // 첨부·인라인 이미지 → S3 inquiries/{inquiryId}/ + 메타 저장.
    // 파일 단위 실패는 건너뛴다 — 첨부 유실보다 메일 본문 유실이 더 나쁘므로 메일 저장은 막지 않는다
    private void saveAttachments(Inquiry inquiry, Message msg) {
        try {
            java.util.List<MailFile> files = new java.util.ArrayList<>();
            collectFiles(msg, files);
            int stored = 0;
            for (MailFile f : files) {
                if (stored >= MAX_ATTACHMENTS) {
                    log.warn("첨부 개수 상한 초과 — 나머지 생략: inquiry={}, total={}", inquiry.getId(), files.size());
                    break;
                }
                if (f.bytes().length == 0 || f.bytes().length > MAX_ATTACHMENT_BYTES) {
                    log.warn("첨부 크기 제외({}bytes): inquiry={}, file={}", f.bytes().length, inquiry.getId(), f.name());
                    continue;
                }
                try {
                    String key = "inquiries/" + inquiry.getId() + "/"
                            + java.util.UUID.randomUUID().toString().substring(0, 8) + "_" + sanitize(f.name());
                    s3Service.uploadFile(key, f.bytes(), f.contentType());
                    inquiryAttachmentRepository.save(InquiryAttachment.builder()
                            .inquiryId(inquiry.getId())
                            .fileName(f.name())
                            .contentType(f.contentType())
                            .sizeBytes(f.bytes().length)
                            .storagePath(key)
                            .build());
                    stored++;
                } catch (Exception e) {
                    log.warn("첨부 저장 실패(생략): inquiry={}, file={}, error={}", inquiry.getId(), f.name(), e.getMessage());
                }
            }
            if (stored > 0) log.info("문의 첨부 저장: inquiry={}, {}건", inquiry.getId(), stored);
        } catch (Exception e) {
            log.warn("첨부 추출 실패(본문만 저장): inquiry={}, error={}", inquiry.getId(), e.getMessage());
        }
    }

    record MailFile(String name, String contentType, byte[] bytes) {}

    // 첨부(disposition ATTACHMENT·파일명 보유) + 인라인 이미지(image/*)를 재귀 수집
    static void collectFiles(Part part, java.util.List<MailFile> out) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) collectFiles(mp.getBodyPart(i), out);
            return;
        }
        String rawName = part.getFileName();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || rawName != null;
        boolean isInlineImage = part.isMimeType("image/*");
        if (!isAttachment && !isInlineImage) return;

        String name = rawName != null
                ? jakarta.mail.internet.MimeUtility.decodeText(rawName)
                : "inline-" + (out.size() + 1) + guessExt(part.getContentType());
        String contentType = part.getContentType();
        if (contentType != null) contentType = contentType.split(";")[0].trim();
        byte[] bytes = part.getInputStream().readAllBytes();
        out.add(new MailFile(name, contentType, bytes));
    }

    static String guessExt(String contentType) {
        if (contentType == null) return "";
        String ct = contentType.split(";")[0].trim().toLowerCase();
        return switch (ct) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    // S3 키 안전화 — 경로 구분자·제어문자 제거, 과도한 길이 절단
    static String sanitize(String name) {
        String cleaned = name.replaceAll("[/\\\\\\p{Cntrl}]", "_");
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        String trimmed = s.strip();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
