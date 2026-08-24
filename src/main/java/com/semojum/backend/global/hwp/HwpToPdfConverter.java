package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlFooter;
import kr.dogfoot.hwplib.object.bodytext.control.ControlHeader;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.reader.HWPReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * mode a HWP 지원 (2026-08-24) — HWP를 "보이는 그대로"의 PDF로 바꿔 기존 PDF 파이프라인에 태운다.
 * 경로: HWP → ODT(pyhwp, scripts/hwp2odt.py — RelaxNG 검증 우회) → PDF(LibreOffice headless).
 *
 * <p>무료 경로의 확인된 한계(실측 2026-08-24): 다단(2단)이 1단으로 풀리고 쪽 나눔·조판이 원본과 다르다.
 * 표(병합 포함)·이미지·각주·참고문헌은 보존된다. 머리말·꼬리말은 변환기가 페이지 반복 요소로
 * 재현하지 못해 유실되므로, hwplib로 직접 읽어 ODT 본문 시작/끝에 [머리말]/[꼬리말] 마커로 주입한다
 * (mode b 추출과 같은 마커 규칙 — 유저 확정 스펙 b).
 *
 * <p>LibreOffice는 호출마다 전용 프로필 디렉터리를 써서 병렬 변환 시 프로필 잠금 충돌을 피한다.
 */
@Slf4j
@Component
public class HwpToPdfConverter {

    private static final String HEADER_MARK = "[머리말]";
    private static final String FOOTER_MARK = "[꼬리말]";

    @Value("${hwp2pdf.python:python3}")
    private String python;

    @Value("${hwp2pdf.script:/app/hwp2odt.py}")
    private String script;

    @Value("${hwp2pdf.soffice:soffice}")
    private String soffice;

    @Value("${hwp2pdf.timeout-seconds:120}")
    private long timeoutSeconds;

    public byte[] convert(byte[] hwpBytes) {
        // 파싱 가능 여부·암호/배포용 검사 + 머리말·꼬리말 수집 (mode b와 동일한 에러 계약)
        HWPFile hwp = readAndValidate(hwpBytes);
        List<String> headers = collectNotes(hwp, true);
        List<String> footers = collectNotes(hwp, false);

        Path dir = null;
        try {
            dir = Files.createTempDirectory("hwp2pdf");
            Path hwpPath = dir.resolve("in.hwp");
            Path odtPath = dir.resolve("in.odt");
            Files.write(hwpPath, hwpBytes);

            run(dir, python, script, hwpPath.toString(), odtPath.toString());

            if (!headers.isEmpty() || !footers.isEmpty()) {
                byte[] injected = injectTexts(Files.readAllBytes(odtPath), headers, footers);
                Files.write(odtPath, injected);
            }

            run(dir, soffice, "--headless",
                    "-env:UserInstallation=file://" + dir.resolve("lo-profile"),
                    "--convert-to", "pdf", "--outdir", dir.toString(), odtPath.toString());

            Path pdfPath = dir.resolve("in.pdf");
            if (!Files.exists(pdfPath) || Files.size(pdfPath) == 0) {
                throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
            }
            return Files.readAllBytes(pdfPath);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HWP→PDF 변환 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
        } finally {
            cleanup(dir);
        }
    }

    private HWPFile readAndValidate(byte[] hwpBytes) {
        HWPFile hwp;
        try {
            hwp = HWPReader.fromInputStream(new ByteArrayInputStream(hwpBytes));
        } catch (Exception e) {
            log.warn("HWP 파싱 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        if (hwp == null) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        if (hwp.getFileHeader().hasPassword()
                || hwp.getFileHeader().isDistribution()
                || hwp.getFileHeader().isEncryptPublicCertification()) {
            throw new CustomException(ErrorCode.JOB_HWP_UNSUPPORTED);
        }
        return hwp;
    }

    // 문서에 정의된 머리말/꼬리말 텍스트를 정의 순서대로, 중복 없이 수집
    private List<String> collectNotes(HWPFile hwp, boolean header) {
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        for (Section section : hwp.getBodyText().getSectionList()) {
            for (int i = 0; i < section.getParagraphCount(); i++) {
                Paragraph p = section.getParagraph(i);
                if (p.getControlList() == null) continue;
                for (Control control : p.getControlList()) {
                    ParagraphList body = null;
                    if (header && control instanceof ControlHeader h) body = h.getParagraphList();
                    if (!header && control instanceof ControlFooter f) body = f.getParagraphList();
                    if (body == null) continue;
                    String text = noteText(body);
                    if (!text.isBlank()) notes.add(text);
                }
            }
        }
        return new ArrayList<>(notes);
    }

    private String noteText(ParagraphList paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs.getParagraphCount(); i++) {
            try {
                String t = paragraphs.getParagraph(i).getNormalString();
                if (t != null && !t.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t.strip());
                }
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    /**
     * ODT의 content.xml에 머리말/꼬리말 문단을 주입한다 — 머리말은 본문 시작, 꼬리말은 본문 끝.
     * mimetype 엔트리는 ODF 규격상 무압축(STORED) 첫 엔트리여야 하므로 그대로 보존한다.
     */
    static byte[] injectTexts(byte[] odt, List<String> headers, List<String> footers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(odt.length + 4096);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(odt));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("content.xml")) {
                    String xml = new String(data, StandardCharsets.UTF_8);
                    StringBuilder head = new StringBuilder();
                    for (String h : headers) head.append(paragraph(HEADER_MARK + " " + h));
                    StringBuilder tail = new StringBuilder();
                    for (String f : footers) tail.append(paragraph(FOOTER_MARK + " " + f));
                    int bodyOpen = xml.indexOf("<office:text");
                    if (bodyOpen >= 0) {
                        int afterOpen = xml.indexOf('>', bodyOpen) + 1;
                        int bodyClose = xml.lastIndexOf("</office:text>");
                        xml = xml.substring(0, afterOpen) + head
                                + xml.substring(afterOpen, bodyClose) + tail
                                + xml.substring(bodyClose);
                    }
                    data = xml.getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getName().equals("mimetype")) {
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    copy.setCrc(crc.getValue());
                }
                zip.putNextEntry(copy);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String paragraph(String text) {
        return "<text:p>" + text
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</text:p>";
    }

    private void run(Path dir, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("HWP→PDF 단계 타임아웃({}s): {}", timeoutSeconds, command[0]);
            throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
        }
        if (process.exitValue() != 0) {
            log.warn("HWP→PDF 단계 실패({}): {}", command[0],
                    output.substring(0, Math.min(output.length(), 500)));
            throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
        }
    }

    private void cleanup(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
