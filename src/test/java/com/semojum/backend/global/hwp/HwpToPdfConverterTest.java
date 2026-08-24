package com.semojum.backend.global.hwp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HwpToPdfConverterTest {

    // ── ODT 머리말/꼬리말 주입 (순수 로직) ──

    private byte[] sampleOdt(String contentXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] mime = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8);
            ZipEntry mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setMethod(ZipEntry.STORED);
            mimeEntry.setSize(mime.length);
            CRC32 crc = new CRC32();
            crc.update(mime);
            mimeEntry.setCrc(crc.getValue());
            zip.putNextEntry(mimeEntry);
            zip.write(mime);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private String readEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    @Test
    void 머리말은_본문_시작_꼬리말은_본문_끝에_마커와_함께_주입된다() throws Exception {
        String content = "<office:document-content>"
                + "<office:body><office:text text:use-soft-page-breaks=\"true\">"
                + "<text:p>본문 첫 문단</text:p><text:p>본문 끝 문단</text:p>"
                + "</office:text></office:body></office:document-content>";
        byte[] result = HwpToPdfConverter.injectTexts(sampleOdt(content),
                List.of("저널명 <2026>"), List.of("쪽 하단 안내"));

        String xml = readEntry(result, "content.xml");
        int header = xml.indexOf("[머리말] 저널명 &lt;2026&gt;");     // XML 이스케이프 확인
        int first = xml.indexOf("본문 첫 문단");
        int last = xml.indexOf("본문 끝 문단");
        int footer = xml.indexOf("[꼬리말] 쪽 하단 안내");
        assertTrue(header > 0 && first > header, "머리말이 본문 앞에 있어야 함");
        assertTrue(footer > last, "꼬리말이 본문 뒤에 있어야 함");
        assertTrue(xml.indexOf("</office:text>") > footer, "꼬리말은 본문 태그 안쪽");
    }

    @Test
    void mimetype_엔트리는_무압축_그대로_보존된다() throws Exception {
        String content = "<office:document-content><office:body><office:text>"
                + "<text:p>x</text:p></office:text></office:body></office:document-content>";
        byte[] result = HwpToPdfConverter.injectTexts(sampleOdt(content), List.of("h"), List.of());

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry first = in.getNextEntry();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            assertEquals("application/vnd.oasis.opendocument.text",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ── 로컬 실측 E2E (도구 경로를 env로 받아 실행 — CI에서는 건너뜀) ──
    // 실행: HWP2PDF_E2E_FILE=<hwp> HWP2PDF_PYTHON=<python> HWP2PDF_SCRIPT=<hwp2odt.py> ./gradlew test --tests HwpToPdfConverterTest
    @Test
    @EnabledIfEnvironmentVariable(named = "HWP2PDF_E2E_FILE", matches = ".+")
    void 실파일_E2E_PDF가_생성된다() throws Exception {
        HwpToPdfConverter converter = new HwpToPdfConverter();
        ReflectionTestUtils.setField(converter, "python", System.getenv("HWP2PDF_PYTHON"));
        ReflectionTestUtils.setField(converter, "script", System.getenv("HWP2PDF_SCRIPT"));
        ReflectionTestUtils.setField(converter, "soffice",
                System.getenv().getOrDefault("HWP2PDF_SOFFICE", "soffice"));
        ReflectionTestUtils.setField(converter, "timeoutSeconds", 180L);

        byte[] hwp = Files.readAllBytes(Path.of(System.getenv("HWP2PDF_E2E_FILE")));
        byte[] pdf = converter.convert(hwp);

        assertTrue(pdf.length > 1000, "PDF 크기");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        String out = System.getenv("HWP2PDF_E2E_OUT");
        if (out != null) Files.write(Path.of(out), pdf);
        System.out.println("E2E 변환 성공: " + pdf.length + " bytes");
    }
}
