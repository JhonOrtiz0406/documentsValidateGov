package co.com.bancolombia.pdfparser;

import co.com.bancolombia.model.document.gateway.PdfParserGateway;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PdfParserAdapter implements PdfParserGateway {

    // ---- Document identification ----
    private static final Pattern CERTIFICATE_TITLE = Pattern.compile(
            "(?i)OFICINA\\s+DE\\s+REGISTRO\\s+DE\\s+INSTRUMENTOS\\s+P[UÚ]BLICOS");

    // ---- PIN extraction patterns (ordered from most specific to least) ----
    private static final List<Pattern> PIN_PATTERNS = List.of(
            // 1) Exact match: "Certificado generado con el Pin No: <digits>"
            //    Accounts for OCR errors: spaces inside words, missing spaces, etc.
            Pattern.compile(
                    "(?i)C\\s*e\\s*r\\s*t\\s*i\\s*f\\s*i\\s*c\\s*a\\s*d\\s*o\\s+" +
                    "g\\s*e\\s*n\\s*e\\s*r\\s*a\\s*d\\s*o\\s+" +
                    "c\\s*o\\s*n\\s+(?:e\\s*l\\s+)?P\\s*i\\s*n\\s+" +
                    "N\\s*o\\s*[:\\-.]?\\s*(\\d{10,25})"),
            // 2) All joined without spaces (OCR glued): "CertificadogeneradoconelPinNo:<digits>"
            Pattern.compile(
                    "(?i)Certificadogeneradoconel?PinNo[:\\-.]?\\s*(\\d{10,25})"),
            // 3) "Pin No:" or "Pin No :" followed by digits
            Pattern.compile(
                    "(?i)Pin\\s*No\\s*[:\\-.]?\\s*(\\d{10,25})"),
            // 4) "Pin:" or "PIN:" followed by digits
            Pattern.compile(
                    "(?i)P\\s*[iI1]\\s*[nN]\\s*[:\\-.]\\s*(\\d{10,25})"),
            // 5) Fallback: look for a long numeric sequence (19-20 digits typical for SNR PINs)
            //    Only after confirming it's a certificate page
            Pattern.compile(
                    "(\\d{19,25})")
    );

    @Override
    public Mono<String> extractPin(byte[] pdfBytes) {
        return Mono.fromCallable(() -> doExtractPin(pdfBytes))
                .onErrorResume(e -> {
                    log.error("Error extracting PIN from PDF: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private String doExtractPin(byte[] pdfBytes) throws Exception {
        log.info("Starting PDF text extraction ({} bytes)", pdfBytes.length);
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            log.info("PDF has {} pages", totalPages);

            // Strategy 1: Text extraction with PDFBox
            String pin = tryTextExtraction(document, totalPages);
            if (pin != null) return pin;

            // Strategy 2: OCR fallback for scanned/photo documents
            log.info("Text extraction didn't find PIN, trying OCR fallback");
            pin = tryOcrExtraction(document, totalPages);
            if (pin != null) return pin;

            log.warn("No PIN found in PDF via text or OCR");
            return null;
        }
    }

    private String tryTextExtraction(PDDocument document, int totalPages) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();

        // Try each page individually to find the certificate page
        for (int page = 1; page <= totalPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document);

            if (text == null || text.isBlank()) continue;

            log.debug("Page {} text ({} chars): {}", page, text.length(),
                    text.length() > 200 ? text.substring(0, 200) + "..." : text);

            // Check if this is a certificate page
            if (isCertificatePage(text)) {
                log.info("Page {} identified as certificate page", page);
                String pin = findPinInText(text, false);
                if (pin != null) {
                    log.info("PIN found via text extraction on page {}: {}", page, pin);
                    return pin;
                }
            }
        }

        // If no certificate page found, try all text combined
        stripper.setStartPage(1);
        stripper.setEndPage(totalPages);
        String allText = stripper.getText(document);
        if (allText != null && !allText.isBlank()) {
            String pin = findPinInText(allText, false);
            if (pin != null) {
                log.info("PIN found in combined text: {}", pin);
                return pin;
            }
        }

        return null;
    }

    private String tryOcrExtraction(PDDocument document, int totalPages) {
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            Tesseract tesseract = createTesseract();

            int pagesToScan = Math.min(totalPages, 5);

            for (int i = 0; i < pagesToScan; i++) {
                log.info("OCR processing page {}/{}", i + 1, pagesToScan);

                // Render at high DPI for better OCR accuracy
                BufferedImage rawImage = renderer.renderImageWithDPI(i, 300, ImageType.RGB);

                // Apply image preprocessing for blurry/scanned documents
                BufferedImage processedImage = preprocessImage(rawImage);

                String pageText = tesseract.doOCR(processedImage);
                log.debug("OCR page {} extracted {} chars", i + 1, pageText.length());

                if (isCertificatePage(pageText)) {
                    log.info("OCR: Page {} identified as certificate page", i + 1);
                    String pin = findPinInText(pageText, true);
                    if (pin != null) {
                        log.info("PIN found via OCR on page {}: {}", i + 1, pin);
                        return pin;
                    }

                    // If blurry: try higher DPI
                    log.info("OCR: Retrying page {} at 600 DPI for better accuracy", i + 1);
                    rawImage = renderer.renderImageWithDPI(i, 600, ImageType.RGB);
                    processedImage = preprocessImage(rawImage);
                    pageText = tesseract.doOCR(processedImage);
                    pin = findPinInText(pageText, true);
                    if (pin != null) {
                        log.info("PIN found via OCR (600 DPI) on page {}: {}", i + 1, pin);
                        return pin;
                    }
                }
            }

            // Fallback: OCR all pages and search without certificate title check
            StringBuilder fullText = new StringBuilder();
            for (int i = 0; i < pagesToScan; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 300, ImageType.RGB);
                BufferedImage processed = preprocessImage(img);
                fullText.append(tesseract.doOCR(processed)).append("\n");
            }
            String allOcrText = fullText.toString();
            return findPinInText(allOcrText, true);

        } catch (Throwable e) {
            log.warn("OCR fallback failed: {}. Tesseract may not be installed.", e.getMessage());
            return null;
        }
    }

    /**
     * Preprocesses an image for better OCR accuracy:
     * - Convert to grayscale
     * - Increase contrast
     * - Apply simple thresholding (binarization)
     */
    private BufferedImage preprocessImage(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();

        // Apply Otsu-like simple binarization for scanned/photo documents
        BufferedImage binary = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g2 = binary.createGraphics();
        g2.drawImage(gray, 0, 0, null);
        g2.dispose();

        return binary;
    }

    private boolean isCertificatePage(String text) {
        if (text == null) return false;
        String normalized = text.replaceAll("\\s+", " ").toUpperCase();
        return normalized.contains("OFICINA DE REGISTRO DE INSTRUMENTOS")
                || normalized.contains("CERTIFICADO DE TRADICION")
                || normalized.contains("MATRICULA INMOBILIARIA")
                || (normalized.contains("CERTIFICADO") && normalized.contains("PIN NO"));
    }

    String findPinInText(String text, boolean isOcr) {
        if (text == null || text.isBlank()) return null;

        // Clean OCR artifacts: remove random spaces within words
        String cleaned = text;
        if (isOcr) {
            // Normalize common OCR misreads
            cleaned = cleaned
                    .replace("|", "l")
                    .replace("¡", "i")
                    .replace("!", "l");
        }

        // Merge digit sequences that are split across lines (PIN wrapped to next line in PDF).
        // e.g. "230228528472928\n1146" → "2302285284729281146"
        // Replace any run of digits, optional horizontal whitespace, a line break,
        // optional horizontal whitespace, then another run of digits → concatenated digits.
        // Apply repeatedly until no more merges are possible.
        String merged = cleaned;
        String prev;
        do {
            prev = merged;
            merged = prev.replaceAll("(\\d+)[ \\t]*(?:\\r?\\n|\\r)[ \\t]*(\\d+)", "$1$2");
        } while (!merged.equals(prev));

        // Try patterns 1–4 on the merged text first, then on the original
        for (String candidate : List.of(merged, cleaned)) {
            for (int i = 0; i < PIN_PATTERNS.size() - 1; i++) {
                Pattern pattern = PIN_PATTERNS.get(i);
                Matcher matcher = pattern.matcher(candidate);
                if (matcher.find()) {
                    String pin = matcher.group(1).replaceAll("\\s+", "").trim();
                    if (pin.length() >= 10) {
                        log.info("PIN matched by pattern {} (merged={}): {}", i + 1, candidate == merged, pin);
                        return pin;
                    }
                }
            }
        }

        // Pattern 5 (fallback long digits) only if this is a certificate page
        if (isCertificatePage(merged)) {
            Pattern fallback = PIN_PATTERNS.get(PIN_PATTERNS.size() - 1);
            for (String candidate : List.of(merged, cleaned)) {
                Matcher matcher = fallback.matcher(candidate);
                List<String> candidates = new ArrayList<>();
                while (matcher.find()) {
                    candidates.add(matcher.group(1));
                }
                // Prefer sequences of 19-22 digits (typical SNR PIN length)
                for (String c : candidates) {
                    if (c.length() >= 19 && c.length() <= 22) {
                        log.info("PIN matched by fallback pattern ({} digits, merged={}): {}", c.length(), candidate == merged, c);
                        return c;
                    }
                }
            }
        }

        return null;
    }

    private Tesseract createTesseract() {
        // Help tess4j find libtesseract DLL on Windows
        String winInstall = "C:\\Program Files\\Tesseract-OCR";
        if (new java.io.File(winInstall).exists()) {
            String existing = System.getProperty("jna.library.path", "");
            if (!existing.contains(winInstall)) {
                System.setProperty("jna.library.path",
                        existing.isBlank() ? winInstall : existing + java.io.File.pathSeparator + winInstall);
            }
        }

        Tesseract tesseract = new Tesseract();
        String dataPath = getOcrDataPath();
        tesseract.setDatapath(dataPath);

        // Use Spanish if available, otherwise fall back to English (digits are universal)
        String lang = new java.io.File(dataPath, "spa.traineddata").exists() ? "spa" : "eng";
        log.info("Tesseract using language: {} (tessdata: {})", lang, dataPath);
        tesseract.setLanguage(lang);
        tesseract.setPageSegMode(6); // Assume uniform block of text
        tesseract.setOcrEngineMode(1); // LSTM only
        return tesseract;
    }

    private String getOcrDataPath() {
        String[] paths = {
                System.getenv("TESSDATA_PREFIX"),
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata",
                "/usr/share/tessdata",
        };
        for (String path : paths) {
            if (path != null && new java.io.File(path).exists()) {
                log.info("Tesseract tessdata path resolved to: {}", path);
                return path;
            }
        }
        log.warn("No known Tesseract tessdata path found, defaulting to /usr/share/tesseract-ocr/5/tessdata");
        return "/usr/share/tesseract-ocr/5/tessdata";
    }
}
