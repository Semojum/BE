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
}
