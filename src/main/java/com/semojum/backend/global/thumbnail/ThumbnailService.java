package com.semojum.backend.global.thumbnail;

import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class ThumbnailService {

    private static final int WIDTH = 595;
    private static final int HEIGHT = 842;
    private static final int MARGIN = 40;
    private static final int FONT_SIZE = 16;
    private static final int LINE_HEIGHT = 24;

    private Font koreanFont;

    @PostConstruct
    public void init() {
        try (InputStream is = getClass().getResourceAsStream("/fonts/NanumGothic.ttf")) {
            Font baseFont = Font.createFont(Font.TRUETYPE_FONT, is);
            koreanFont = baseFont.deriveFont(Font.PLAIN, FONT_SIZE);
        } catch (Exception e) {
            koreanFont = new Font("SansSerif", Font.PLAIN, FONT_SIZE);
        }
    }

    // PDF 첫 페이지를 PNG로 렌더링
    public byte[] generatePdfThumbnail(byte[] pdfBytes) throws IOException {
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
