package co.com.bancolombia.pdfparser;

import co.com.bancolombia.model.document.gateway.PdfParserGateway;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PdfParserAdapter implements PdfParserGateway {

    // Regex patterns for PIN extraction from Colombian notarial certificates
    private static final Pattern[] PIN_PATTERNS = {
            // Direct PIN label
            Pattern.compile("(?i)(?:PIN|C[oó]digo\\s+de\\s+[Vv]erificaci[oó]n|N[uú]mero\\s+de\\s+[Vv]erificaci[oó]n)\\s*[:\\-]?\\s*([A-Za-z0-9\\-]{6,30})"),
            // Alphanumeric code pattern (common in SNR certificates)
            Pattern.compile("(?i)verificaci[oó]n[^\\n]{0,50}?([A-Z0-9]{8,20})"),
            // Generic alphanumeric code with hyphens
            Pattern.compile("\\b([A-Z0-9]{4,8}-[A-Z0-9]{4,8}(?:-[A-Z0-9]{4,8})?)\\b"),
            // Fallback: long alphanumeric sequence
            Pattern.compile("\\b([A-Z0-9]{10,25})\\b")
    };

    @Override
    public Mono<String> extractPin(byte[] pdfBytes) {
        return Mono.fromCallable(() -> {
            log.info("Starting PDF text extraction ({} bytes)", pdfBytes.length);
            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                // First try text extraction
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);

                if (text != null && !text.isBlank()) {
                    log.info("Extracted text from PDF ({} chars)", text.length());
                    String pin = findPin(text);
                    if (pin != null) {
                        log.info("PIN found via text extraction: {}", pin);
                        return pin;
                    }
                }

                // fallback to OCR
                log.info("Text extraction didn't find PIN, trying OCR fallback");
                return extractPinWithOcr(document);
            }
        });
    }

    private String extractPinWithOcr(PDDocument document) {
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(getOcrDataPath());
            tesseract.setLanguage("spa");

            StringBuilder fullText = new StringBuilder();
            int pages = Math.min(document.getNumberOfPages(), 5); // Limit to first 5 pages

            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 300);
                String pageText = tesseract.doOCR(image);
                fullText.append(pageText).append("\n");
            }

            String ocrText = fullText.toString();
            log.info("OCR extracted {} chars", ocrText.length());

            String pin = findPin(ocrText);
            if (pin != null) {
                log.info("PIN found via OCR: {}", pin);
                return pin;
            }

            log.warn("No PIN found in PDF via text or OCR");
            return null;
        } catch (Exception e) {
            log.warn("OCR fallback failed: {}. Tesseract may not be installed.", e.getMessage());
            return null;
        }
    }

    private String findPin(String text) {
        for (Pattern pattern : PIN_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private String getOcrDataPath() {
        // Try common Tesseract data paths
        String[] paths = {
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata",
                "/usr/share/tessdata",
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                System.getenv("TESSDATA_PREFIX")
        };

        for (String path : paths) {
            if (path != null && new java.io.File(path).exists()) {
                return path;
            }
        }

        return "/usr/share/tesseract-ocr/5/tessdata"; // default
    }
}
