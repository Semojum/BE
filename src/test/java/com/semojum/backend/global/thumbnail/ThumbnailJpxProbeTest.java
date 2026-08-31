package com.semojum.backend.global.thumbnail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실파일 프로브 — JPEG 2000 이미지 PDF의 첫 장 썸네일이 백지가 아닌지 확인 (env 게이트, CI 미실행).
 * 실행: THUMB_PROBE_FILE=<pdf 경로> ./gradlew test --tests ThumbnailJpxProbeTest --rerun
 */
@EnabledIfEnvironmentVariable(named = "THUMB_PROBE_FILE", matches = ".+")
class ThumbnailJpxProbeTest {

    @Test
    void thumbnailIsNotBlank() throws Exception {
        byte[] pdf = Files.readAllBytes(Path.of(System.getenv("THUMB_PROBE_FILE")));
        ThumbnailService service = new ThumbnailService(new com.semojum.backend.global.pdf.PdfPageRenderer(
                System.getenv().getOrDefault("THUMB_PROBE_PDFTOPPM", "pdftoppm"), 30, 85));
        service.init();
        byte[] png = service.generatePdfThumbnail(pdf);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

        long dark = 0, total = (long) img.getWidth() * img.getHeight();
        for (int y = 0; y < img.getHeight(); y += 2)
            for (int x = 0; x < img.getWidth(); x += 2) {
                int rgb = img.getRGB(x, y);
                int lum = ((rgb >> 16 & 0xff) + (rgb >> 8 & 0xff) + (rgb & 0xff)) / 3;
                if (lum < 200) dark++;
            }
        double darkRatio = dark * 4.0 / total;
        System.out.printf("썸네일 %dx%d, png %d bytes, 어두운 픽셀 비율 %.2f%%%n",
                img.getWidth(), img.getHeight(), png.length, darkRatio * 100);
        String out = System.getenv("THUMB_PROBE_OUT");
        if (out != null) Files.write(Path.of(out), png);
        assertTrue(darkRatio > 0.005, "썸네일이 백지다 (어두운 픽셀 " + darkRatio + ")");
    }
}
