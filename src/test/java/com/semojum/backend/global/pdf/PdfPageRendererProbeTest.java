package com.semojum.backend.global.pdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실파일 프로브 — 실제 PDF가 JPEG로 렌더되는지, 얼마나 걸리는지 확인 (env 게이트, CI 미실행).
 * 실행: RENDER_PROBE_FILES="a.pdf,b.pdf" ./gradlew test --tests PdfPageRendererProbeTest --rerun
 */
@EnabledIfEnvironmentVariable(named = "RENDER_PROBE_FILES", matches = ".+")
class PdfPageRendererProbeTest {

    @Test
    void 실파일을_JPEG로_렌더한다() throws Exception {
        PdfPageRenderer renderer = new PdfPageRenderer("pdftoppm", 60, 85);
        for (String file : System.getenv("RENDER_PROBE_FILES").split(",")) {
            Path path = Path.of(file.trim());
            byte[] pdf = Files.readAllBytes(path);
            long t0 = System.currentTimeMillis();
            byte[] jpeg = renderer.renderFirstPage(pdf, PdfPageRenderer.Format.JPEG, 150);
            long elapsed = System.currentTimeMillis() - t0;

            Path out = Path.of(path.toString().replace(".pdf", "_rendered150.jpg"));
            Files.write(out, jpeg);
            System.out.printf("%s: PDF %dKB → JPEG %dKB, 렌더 %dms → %s%n",
                    path.getFileName(), pdf.length / 1024, jpeg.length / 1024, elapsed, out);

            // JPEG 시그니처(FF D8 FF) 확인 — 빈 파일·오류 출력이 아닌 진짜 이미지인지
            assertTrue(jpeg.length > 1000 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8,
                    "JPEG가 아니거나 너무 작다: " + path);
        }
    }
}
