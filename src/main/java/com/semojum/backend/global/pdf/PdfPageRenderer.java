package com.semojum.backend.global.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PDF 한 쪽을 이미지로 렌더한다 — poppler {@code pdftoppm}(별도 프로세스) 사용.
 *
 * <p><b>왜 JVM 밖에서 푸는가</b>: PDFBox는 JPEG 2000(JPXDecode) 이미지를 못 그려 스캔본이 백지가 되고,
 * 순수 Java JPX 디코더(jai-imageio-jpeg2000)는 같은 파일에서 768MB 힙(운영 -Xmx)에서도 OOM이 났다
 * (2026-08-27 실측). pdftoppm은 OpenJPEG를 내장하고 메모리가 프로세스로 격리돼 JVM을 위협하지 않는다.
 *
 * <p>도구는 Dockerfile에 내장(poppler-utils). 미설치 환경에서는 예외를 던지므로 호출부가 폴백한다.
 */
@Slf4j
@Component
public class PdfPageRenderer {

    public enum Format {
        PNG("-png", "png"),
        JPEG("-jpeg", "jpg");

        private final String flag;
        private final String ext;

        Format(String flag, String ext) {
            this.flag = flag;
            this.ext = ext;
        }
    }

    private final String pdftoppm;
    private final int timeoutSeconds;
    private final int jpegQuality;

    public PdfPageRenderer(@Value("${pdf-render.pdftoppm:pdftoppm}") String pdftoppm,
                           @Value("${pdf-render.timeout-seconds:60}") int timeoutSeconds,
                           @Value("${pdf-render.jpeg-quality:85}") int jpegQuality) {
        this.pdftoppm = pdftoppm;
        this.timeoutSeconds = timeoutSeconds;
        this.jpegQuality = jpegQuality;
    }

    /**
     * 첫 쪽을 지정 형식·해상도로 렌더한다. 단일 쪽 PDF를 넘기는 것이 전제(페이지 분리 결과물).
     *
     * @throws IOException          렌더 실패·타임아웃·도구 미설치
     * @throws InterruptedException 프로세스 대기 중 인터럽트 (호출부가 인터럽트 상태를 복구할 것)
     */
    public byte[] renderFirstPage(byte[] pdfBytes, Format format, int dpi) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("pdfrender-");
        try {
            Path in = dir.resolve("in.pdf");
            Files.write(in, pdfBytes);

            List<String> command = new ArrayList<>(List.of(
                    pdftoppm, "-f", "1", "-l", "1", "-r", String.valueOf(dpi),
                    format.flag, "-singlefile"));
            if (format == Format.JPEG) {
                command.addAll(List.of("-jpegopt", "quality=" + jpegQuality));
            }
            command.add(in.toString());
            command.add(dir.resolve("out").toString());

            Process process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("pdftoppm 타임아웃(" + timeoutSeconds + "s)");
            }
            Path out = dir.resolve("out." + format.ext);
            if (process.exitValue() != 0 || !Files.exists(out)) {
                throw new IOException("pdftoppm exit=" + process.exitValue() + " "
                        + output.substring(0, Math.min(output.length(), 300)));
            }
            return Files.readAllBytes(out);
        } finally {
            cleanup(dir);
        }
    }

    private void cleanup(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.debug("임시 디렉터리 정리 실패: {}", dir);
        }
    }
}
