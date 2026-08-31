package com.semojum.backend.global.thumbnail;

import com.semojum.backend.global.pdf.PdfPageRenderer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ThumbnailService {

    private static final int WIDTH = 595;
    private static final int HEIGHT = 842;
    private static final int MARGIN = 40;
    private static final int FONT_SIZE = 16;
    private static final int LINE_HEIGHT = 24;

    private Font koreanFont;

    // 첫 장 렌더러: poppler pdftoppm(별도 프로세스, PdfPageRenderer) 우선, 없거나 실패하면 PDFBox 폴백.
    // PDFBox(JVM 내 렌더)는 JPEG 2000(JPXDecode) 이미지를 못 그려 스캔본 썸네일이 백지가 되고,
    // 순수 Java JPX 디코더(jai-imageio-jpeg2000)는 일부 파일에서 768MB 힙에서도 OOM(2026-08-27 실측)이라
    // JVM 안에서 풀 수 없다. 도구는 Dockerfile(poppler-utils)에 내장, 로컬은 미설치 시 자동 폴백
    private static final int THUMBNAIL_DPI = 100;

    private final PdfPageRenderer pdfPageRenderer;

    public ThumbnailService(PdfPageRenderer pdfPageRenderer) {
        this.pdfPageRenderer = pdfPageRenderer;
    }

    @PostConstruct
    public void init() {
        try (InputStream is = getClass().getResourceAsStream("/fonts/NanumGothic.ttf")) {
            Font baseFont = Font.createFont(Font.TRUETYPE_FONT, is);
            koreanFont = baseFont.deriveFont(Font.PLAIN, FONT_SIZE);
        } catch (Exception e) {
            koreanFont = new Font("SansSerif", Font.PLAIN, FONT_SIZE);
        }
    }

    // PDF 첫 페이지를 PNG로 렌더링 — pdftoppm 우선, 실패 시 PDFBox 폴백
    public byte[] generatePdfThumbnail(byte[] pdfBytes) throws IOException {
        try {
            return pdfPageRenderer.renderFirstPage(pdfBytes, PdfPageRenderer.Format.PNG, THUMBNAIL_DPI);
        } catch (IOException | InterruptedException e) {
            log.info("pdftoppm 썸네일 실패, PDFBox 폴백: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return renderFirstPageWithPdfBox(pdfBytes);
    }

    private byte[] renderFirstPageWithPdfBox(byte[] pdfBytes) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 100);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    // 텍스트를 흰 배경 위에 렌더링하여 PNG로 변환
    public byte[] generateTextThumbnail(String text) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.BLACK);
        g.setFont(koreanFont);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        FontMetrics fm = g.getFontMetrics();
        int maxLineWidth = WIDTH - (MARGIN * 2);
        int y = MARGIN + fm.getAscent();
        int maxY = HEIGHT - MARGIN;

        for (String rawLine : text.split("\n")) {
            if (y > maxY) break;
            for (String wrapped : wrapLine(rawLine, fm, maxLineWidth)) {
                if (y > maxY) break;
                g.drawString(wrapped, MARGIN, y);
                y += LINE_HEIGHT;
            }
        }

        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private List<String> wrapLine(String line, FontMetrics fm, int maxWidth) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (char c : line.toCharArray()) {
            current.append(c);
            if (fm.stringWidth(current.toString()) > maxWidth) {
                current.setLength(current.length() - 1);
                result.add(current.toString());
                current = new StringBuilder();
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }
}
