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

    // ── 경로 입력 (2026-10-16 — 원본을 힙에 올리지 않는다) ──

    /** 깨진 HWP — 종전과 같은 에러(JOB4007)이고, 원본 파일은 호출부 소유라 지우지 않는다 */
    @Test
    void 깨진_HWP는_JOB4007이고_원본은_남는다() throws Exception {
        Path src = Files.createTempFile("broken-", ".hwp");
        try {
            Files.write(src, "not a hwp".getBytes(StandardCharsets.UTF_8));
            com.semojum.backend.global.exception.CustomException e = assertThrows(
                    com.semojum.backend.global.exception.CustomException.class,
                    () -> new HwpToPdfConverter().convert(src));
            assertEquals(com.semojum.backend.global.exception.ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
            assertTrue(Files.exists(src));
        } finally {
            Files.deleteIfExists(src);
        }
    }

    /** 내용물이 ZIP이면 확장자와 무관하게 HWPX 경로로 간다 — HWPX가 아닌 ZIP(docx 등)은 JOB4007 */
    @Test
    void ZIP_시그니처는_앞_4바이트로_가른다() throws Exception {
        Path src = Files.createTempFile("fake-", ".hwp");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write("<w/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.write(src, bos.toByteArray());
            com.semojum.backend.global.exception.CustomException e = assertThrows(
                    com.semojum.backend.global.exception.CustomException.class,
                    () -> new HwpToPdfConverter().convert(src));
            assertEquals(com.semojum.backend.global.exception.ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
        } finally {
            Files.deleteIfExists(src);
        }
    }

    /** 결과 PDF 폴더는 close()로 지운다 — 업로드 처리가 try-with-resources로 쓴다 */
    @Test
    void 변환_결과는_닫으면_폴더째_지워진다() throws Exception {
        Path dir = Files.createTempDirectory("hwp2pdf-close");
        Path pdf = dir.resolve("in.pdf");
        Files.write(pdf, new byte[]{'%', 'P', 'D', 'F'});
        try (HwpToPdfConverter.ConvertedPdf converted = new HwpToPdfConverter.ConvertedPdf(pdf, dir)) {
            assertTrue(Files.exists(converted.pdf()));
        }
        assertFalse(Files.exists(dir));
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

        byte[] pdf;
        try (HwpToPdfConverter.ConvertedPdf converted = converter.convert(Path.of(System.getenv("HWP2PDF_E2E_FILE")))) {
            pdf = Files.readAllBytes(converted.pdf());
        }

        assertTrue(pdf.length > 1000, "PDF 크기");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        String out = System.getenv("HWP2PDF_E2E_OUT");
        if (out != null) Files.write(Path.of(out), pdf);
        System.out.println("E2E 변환 성공: " + pdf.length + " bytes");
    }
}
