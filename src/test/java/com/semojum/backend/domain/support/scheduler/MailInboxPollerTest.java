package com.semojum.backend.domain.support.scheduler;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// 메일 파싱 규칙 — 인메모리 MimeMessage로 검증 (IMAP 연결은 실서버에서)
class MailInboxPollerTest {

    private MimeMessage message() {
        return new MimeMessage((Session) null);
    }

    @Test
    void 보낸_사람은_메일_주소만() throws Exception {
        MimeMessage msg = message();
        msg.setFrom(new InternetAddress("hong@kblib.or.kr", "홍길동"));
        assertEquals("hong@kblib.or.kr", MailInboxPoller.senderAddress(msg));
    }

    @Test
    void 단일_텍스트_본문() throws Exception {
        MimeMessage msg = message();
        msg.setText("도입 문의드립니다");
        msg.saveChanges();
        assertEquals("도입 문의드립니다", MailInboxPoller.extractText(msg));
    }

    @Test
    void 멀티파트는_plain_우선_없으면_html() throws Exception {
        // plain + html → plain 선택
        MimeMessage both = message();
        MimeMultipart mp = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("텍스트 본문");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>HTML 본문</p>", "text/html; charset=utf-8");
        mp.addBodyPart(plain);
        mp.addBodyPart(html);
        both.setContent(mp);
        both.saveChanges();
        assertEquals("텍스트 본문", MailInboxPoller.extractText(both));

        // html만 → html 원문이라도 반환 (내용 유실 방지)
        MimeMessage htmlOnly = message();
        MimeMultipart mp2 = new MimeMultipart();
        MimeBodyPart html2 = new MimeBodyPart();
        html2.setContent("<p>HTML만</p>", "text/html; charset=utf-8");
        mp2.addBodyPart(html2);
        htmlOnly.setContent(mp2);
        htmlOnly.saveChanges();
        assertEquals("<p>HTML만</p>", MailInboxPoller.extractText(htmlOnly));
    }

    @Test
    void 중첩_멀티파트에서도_본문_추출() throws Exception {
        MimeMessage msg = message();
        MimeMultipart inner = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("중첩 본문");
        inner.addBodyPart(plain);
        MimeBodyPart innerWrap = new MimeBodyPart();
        innerWrap.setContent(inner);
        MimeMultipart outer = new MimeMultipart("mixed");
        outer.addBodyPart(innerWrap);
        msg.setContent(outer);
        msg.saveChanges();
        assertEquals("중첩 본문", MailInboxPoller.extractText(msg));
    }

    @Test
    void 자르기와_공백_정리() {
        assertEquals("abc", MailInboxPoller.truncate("  abc  ", 10));
        assertEquals("ab", MailInboxPoller.truncate("abcdef", 2));
        assertNull(MailInboxPoller.truncate(null, 10));
    }

    @Test
    void 첨부와_인라인_이미지를_수집하고_본문은_제외한다() throws Exception {
        var session = jakarta.mail.Session.getInstance(new java.util.Properties());
        var msg = new jakarta.mail.internet.MimeMessage(session);
        var mp = new jakarta.mail.internet.MimeMultipart();

        var body = new jakarta.mail.internet.MimeBodyPart();
        body.setText("본문입니다");
        mp.addBodyPart(body);

        var file = new jakarta.mail.internet.MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(
                new jakarta.mail.util.ByteArrayDataSource(new byte[]{1, 2, 3}, "application/pdf")));
        file.setFileName("견적요청.pdf");
        file.setDisposition(jakarta.mail.Part.ATTACHMENT);
        mp.addBodyPart(file);

        var image = new jakarta.mail.internet.MimeBodyPart();   // 파일명 없는 인라인 이미지
        image.setDataHandler(new jakarta.activation.DataHandler(
                new jakarta.mail.util.ByteArrayDataSource(new byte[]{9, 9}, "image/png")));
        image.setDisposition(jakarta.mail.Part.INLINE);
        mp.addBodyPart(image);

        msg.setContent(mp);
        msg.saveChanges();

        var out = new java.util.ArrayList<MailInboxPoller.MailFile>();
        MailInboxPoller.collectFiles(msg, out);

        org.junit.jupiter.api.Assertions.assertEquals(2, out.size());
        org.junit.jupiter.api.Assertions.assertEquals("견적요청.pdf", out.get(0).name());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 2, 3}, out.get(0).bytes());
        org.junit.jupiter.api.Assertions.assertTrue(out.get(1).name().startsWith("inline-"));
        org.junit.jupiter.api.Assertions.assertTrue(out.get(1).name().endsWith(".png"));
    }

    @Test
    void 파일명_경로문자는_S3키에서_치환() {
        String cleaned = MailInboxPoller.sanitize("../etc/passwd");
        org.junit.jupiter.api.Assertions.assertFalse(cleaned.contains("/"));
        org.junit.jupiter.api.Assertions.assertFalse(MailInboxPoller.sanitize("a\\b").contains("\\"));
    }
}
