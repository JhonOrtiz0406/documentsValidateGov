package co.com.bancolombia.pdfparser;

import co.com.bancolombia.model.document.PinExtractionResult;
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
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PdfParserAdapter implements PdfParserGateway {

    // ── Certificate-page keywords ─────────────────────────────────────────────────
    private static final List<String> CERT_KEYWORDS = List.of(
            "OFICINA DE REGISTRO DE INSTRUMENTOS",
            "CERTIFICADO DE TRADICION",
            "CERTIFICADO DE LIBERTAD",
            "CERIFICADO DE LIBERTAD",   // common misspelling in real docs
            "CERIFICADO DE TRADICION",
            "MATRICULA INMOBILIARIA",
            "MATRÍCULA INMOBILIARIA"
    );

    // ── PIN digit block ───────────────────────────────────────────────────────────
    // Captures digits optionally separated by spaces/tabs (PDFBox splits PDF text
    // elements with spaces; long numbers may also wrap across lines).
    // Post-processing: strip whitespace, validate as 10-25 pure digits.
    private static final String DB = "(\\d[\\d \\t]{8,28}\\d)";

    // ── PIN-label patterns, most specific → least specific ───────────────────────
    private static final List<Pattern> LABELED = List.of(

        // ── A: full phrase "Certificado generado con el Pin No:" ─────────────────
        // A1 – normal word spacing
        Pattern.compile(
            "(?i)Certificado\\s+generado\\s+con\\s+(?:el\\s+)?[Pp]in\\s+[Nn]o\\s*[:\\-.]?\\s*" + DB),
        // A2 – OCR inserts spaces inside each word
        Pattern.compile(
            "(?i)C\\s*e\\s*r\\s*t\\s*i\\s*f\\s*i\\s*c\\s*a\\s*d\\s*o\\s+" +
            "g\\s*e\\s*n\\s*e\\s*r\\s*a\\s*d\\s*o\\s+" +
            "c\\s*o\\s*n\\s+(?:e\\s*l\\s+)?P\\s*i\\s*n\\s+N\\s*o\\s*[:\\-.]?\\s*" + DB),
        // A3 – OCR glued all words
        Pattern.compile("(?i)Certificadogeneradoconel?PinNo[:\\-.]?\\s*" + DB),

        // ── B: "Pin No:" variants ────────────────────────────────────────────────
        // B1 – standard
        Pattern.compile("(?i)Pin\\s*No\\s*[:\\-./]?\\s*" + DB),
        // B2 – OCR spaced: "P i n   N o :"
        Pattern.compile("(?i)P\\s*i\\s*n\\s+N\\s*o\\s*[:\\-./]?\\s*" + DB),
        // B3 – degree/ordinal: "Pin N°" / "Pin Nº"
        Pattern.compile("(?i)Pin\\s*N[°º]\\s*[:\\-.]?\\s*" + DB),

        // ── C: bare "PIN:" label and Colombian document variants ─────────────────
        // C1 – "PIN:" / "Pin:" with separator
        Pattern.compile("(?i)P\\s*[iI1]\\s*[nN]\\s*[:\\-.]\\s*" + DB),
        // C2 – "Número de Pin:" / "Numero de pin:"
        Pattern.compile("(?i)N[uú]mero\\s+de\\s+[Pp]in\\s*[:\\-.]?\\s*" + DB),
        // C3 – "Código PIN:" / "Cod. PIN:"
        Pattern.compile("(?i)C[oó]d(?:igo)?[.\\s]*\\s*[Pp]in\\s*[:\\-.]?\\s*" + DB),
        // C4 – "No. de pin:" / "No de pin:"
        Pattern.compile("(?i)No\\.?\\s+de\\s+[Pp]in\\s*[:\\-.]?\\s*" + DB),
        // C5 – any line starting with a long digit run right after "pin" keyword
        //      e.g. "...pin\n2302285284729281146"  (label on previous line)
        Pattern.compile("(?i)pin[^\\d\\n]{0,30}\\n[ \\t]*" + DB)
    );

    // Fallback: raw 15-25 digit run — only on confirmed certificate pages
    private static final Pattern FALLBACK = Pattern.compile("(\\d{15,25})");

    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public Mono<PinExtractionResult> extractPins(byte[] pdfBytes) {
        return Mono.fromCallable(() -> doExtractPins(pdfBytes))
                .onErrorResume(e -> {
                    log.error("Error extracting PINs from PDF: {}", e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private PinExtractionResult doExtractPins(byte[] pdfBytes) throws Exception {
        log.info("Starting PDF PIN extraction ({} bytes)", pdfBytes.length);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int totalPages = doc.getNumberOfPages();
            log.info("PDF has {} pages", totalPages);

            PinExtractionResult result = tryTextExtraction(doc, totalPages);
            if (result != null) return result;

            log.info("Text extraction yielded no PIN; starting OCR");
            result = tryOcrExtraction(doc, totalPages);
            if (result != null) return result;

            log.warn("No PIN found in PDF (neither text nor OCR)");
            return null;
        }
    }

    // ── Strategy 1: PDFBox text layer ─────────────────────────────────────────────

    private PinExtractionResult tryTextExtraction(PDDocument doc, int totalPages) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();

        // cert pages → used for multi-page conflict detection
        LinkedHashMap<Integer, String> certPagePins = new LinkedHashMap<>();
        // any page that has a labeled PIN (even non-cert) → used as fallback
        String firstLabeledPin = null;

        for (int page = 1; page <= totalPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                log.info("Page {} — empty (scanned image or no text layer)", page);
                continue;
            }

            logPageText(page, text);

            boolean isCert = isCertificatePage(text);
            log.info("Page {} — isCertPage={}, chars={}", page, isCert, text.length());

            // Try ALL labeled patterns on EVERY page
            String pin = findWithLabeledPatterns(text, false);

            if (pin != null) {
                log.info("Page {} — labeled-pattern PIN: {}", page, pin);
                if (isCert) {
                    certPagePins.put(page, pin);
                } else if (firstLabeledPin == null) {
                    firstLabeledPin = pin;
                }
            } else if (isCert) {
                // Cert page but labeled pattern didn't match → try fallback digits
                pin = findWithFallback(text);
                if (pin != null) {
                    log.info("Page {} — fallback-digit PIN: {}", page, pin);
                    certPagePins.put(page, pin);
                } else {
                    log.warn("Page {} — cert page but no PIN extracted", page);
                }
            }
        }

        // Build result: cert-page conflict check takes priority
        if (!certPagePins.isEmpty()) {
            return buildResult(certPagePins);
        }
        if (firstLabeledPin != null) {
            return PinExtractionResult.single(firstLabeledPin);
        }
        return null;
    }

    // ── Strategy 2: Tesseract OCR ─────────────────────────────────────────────────

    private PinExtractionResult tryOcrExtraction(PDDocument doc, int totalPages) {
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            Tesseract tess = createTesseract();
            int pagesToScan = Math.min(totalPages, 6);

            LinkedHashMap<Integer, String> certPagePins = new LinkedHashMap<>();
            String firstLabeledPin = null;

            for (int i = 0; i < pagesToScan; i++) {
                log.info("OCR page {}/{}", i + 1, pagesToScan);
                String text = ocrPage(renderer, tess, i, 300);
                if (text.isBlank()) continue;

                logPageText(i + 1, text);

                boolean isCert = isCertificatePage(text);

                String pin = findWithLabeledPatterns(text, true);
                if (pin == null && isCert) {
                    // Retry at 600 DPI for better accuracy on blurry pages
                    log.info("OCR: retrying page {} at 600 DPI", i + 1);
                    text = ocrPage(renderer, tess, i, 600);
                    pin = findWithLabeledPatterns(text, true);
                    if (pin == null) pin = findWithFallback(text);
                }

                if (pin != null) {
                    log.info("OCR page {} PIN: {}", i + 1, pin);
                    if (isCert) certPagePins.put(i + 1, pin);
                    else if (firstLabeledPin == null) firstLabeledPin = pin;
                }
            }

            if (!certPagePins.isEmpty()) return buildResult(certPagePins);
            if (firstLabeledPin != null) return PinExtractionResult.single(firstLabeledPin);

            // Last resort: OCR all pages combined
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < pagesToScan; i++)
                all.append(ocrPage(renderer, tess, i, 300)).append("\n");
            String pin = findBestPinInText(all.toString(), true);
            return pin != null ? PinExtractionResult.single(pin) : null;

        } catch (Throwable e) {
            log.warn("OCR extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private String ocrPage(PDFRenderer renderer, Tesseract tess, int idx, int dpi) {
        try {
            BufferedImage img = renderer.renderImageWithDPI(idx, dpi, ImageType.RGB);
            return tess.doOCR(preprocessImage(img));
        } catch (Exception e) {
            log.warn("OCR failed page {} at {} DPI: {}", idx + 1, dpi, e.getMessage());
            return "";
        }
    }

    // ── Core PIN-extraction helpers ───────────────────────────────────────────────

    /** Full extraction pipeline: merge split digits, try labeled patterns, then fallback. */
    String findBestPinInText(String text, boolean isOcr) {
        if (text == null || text.isBlank()) return null;
        String pin = findWithLabeledPatterns(text, isOcr);
        if (pin != null) return pin;
        if (isCertificatePage(text)) return findWithFallback(text);
        return null;
    }

    /** Try every labeled pattern (A–C5). Merges newline/space-split digits first. */
    private String findWithLabeledPatterns(String text, boolean isOcr) {
        String cleaned = isOcr
                ? text.replace("|", "I").replace("¡", "i").replace("!", "I")
                : text;

        // Merge digits split by newlines
        String merged = mergeNewlineSplitDigits(cleaned);

        for (int i = 0; i < LABELED.size(); i++) {
            String pin = applyPattern(LABELED.get(i), merged, "L" + (i + 1));
            if (pin != null) return pin;
        }
        return null;
    }

    /** Fallback: find any 15-25 digit run; prefer 19-22 digits (typical SNR PIN). */
    private String findWithFallback(String text) {
        String merged = mergeNewlineSplitDigits(text);
        Matcher m = FALLBACK.matcher(merged);
        String best = null;
        while (m.find()) {
            String c = m.group(1);
            if (c.length() >= 19 && c.length() <= 22) {
                log.info("Fallback PIN ({} digits): {}", c.length(), c);
                return c;
            }
            if (best == null && c.length() >= 15) best = c;
        }
        if (best != null) log.info("Fallback PIN (backup {} digits): {}", best.length(), best);
        return best;
    }

    /**
     * Apply a single pattern. The capture group DB allows spaces/tabs within digits;
     * we strip them and validate as 10-25 pure digits.
     */
    private String applyPattern(Pattern p, String text, String label) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            String pin = raw.replaceAll("[ \\t]+", "");
            if (pin.matches("\\d{10,25}")) {
                log.info("Pattern {} matched PIN: {}", label, pin);
                return pin;
            }
            log.debug("Pattern {} matched '{}' but stripped '{}' failed length check", label, raw, pin);
        }
        return null;
    }

    /** Repeatedly merge digit sequences split only by a newline. */
    private String mergeNewlineSplitDigits(String text) {
        String prev, current = text;
        do {
            prev = current;
            current = prev.replaceAll("(\\d+)[ \\t]*(?:\\r?\\n|\\r)[ \\t]*(\\d+)", "$1$2");
        } while (!current.equals(prev));
        return current;
    }

    // ── Result builder ────────────────────────────────────────────────────────────

    private PinExtractionResult buildResult(LinkedHashMap<Integer, String> pagePins) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(pagePins.values()));
        if (unique.size() == 1) {
            log.info("All certificate pages agree on PIN: {}", unique.get(0));
            return PinExtractionResult.single(unique.get(0));
        }
        log.warn("CONFLICT: multiple PINs across pages: {}", unique);
        return PinExtractionResult.conflict(unique);
    }

    // ── Certificate-page detection ─────────────────────────────────────────────────

    private boolean isCertificatePage(String text) {
        if (text == null) return false;
        String up = text.replaceAll("\\s+", " ").toUpperCase();
        for (String kw : CERT_KEYWORDS) {
            if (up.contains(kw)) return true;
        }
        // Also treat as cert page when the certificate+PIN combo appears
        return (up.contains("CERTIFICADO") || up.contains("CERIFICADO"))
                && (up.contains("PIN NO") || up.contains("PIN N°") || up.contains("PIN N"));
    }

    // ── Verbose page-text logging ──────────────────────────────────────────────────

    private void logPageText(int page, String text) {
        if (text == null || text.isBlank()) return;

        // Always log a compact header
        log.info("=== PAGE {} TEXT ({} chars) ===", page, text.length());

        // Log the first 800 chars unconditionally
        String head = text.length() > 800 ? text.substring(0, 800) + "…" : text;
        log.info("PAGE {} HEAD: {}", page, head.replace("\n", "↵").replace("\r", ""));

        // If "pin" appears anywhere, log the 300-char window around it
        String lower = text.toLowerCase();
        int pinIdx = lower.indexOf("pin");
        if (pinIdx >= 0) {
            int start = Math.max(0, pinIdx - 50);
            int end   = Math.min(text.length(), pinIdx + 250);
            String window = text.substring(start, end);
            log.info("PAGE {} PIN-WINDOW: [{}]",
                    page, window.replace("\n", "↵").replace("\r", ""));
        }
    }

    // ── Image preprocessing ────────────────────────────────────────────────────────

    private BufferedImage preprocessImage(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        BufferedImage bin = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g2 = bin.createGraphics();
        g2.drawImage(gray, 0, 0, null);
        g2.dispose();
        return bin;
    }

    // ── Tesseract setup ───────────────────────────────────────────────────────────

    private Tesseract createTesseract() {
        String winInstall = "C:\\Program Files\\Tesseract-OCR";
        if (new java.io.File(winInstall).exists()) {
            String existing = System.getProperty("jna.library.path", "");
            if (!existing.contains(winInstall))
                System.setProperty("jna.library.path",
                        existing.isBlank() ? winInstall : existing + java.io.File.pathSeparator + winInstall);
        }
        Tesseract t = new Tesseract();
        String dataPath = getOcrDataPath();
        t.setDatapath(dataPath);
        String lang = new java.io.File(dataPath, "spa.traineddata").exists() ? "spa" : "eng";
        log.info("Tesseract language: {} (tessdata: {})", lang, dataPath);
        t.setLanguage(lang);
        t.setPageSegMode(3);  // Auto page segmentation (better for full documents)
        t.setOcrEngineMode(1);
        return t;
    }

    private String getOcrDataPath() {
        String[] paths = {
                System.getenv("TESSDATA_PREFIX"),
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata",
                "/usr/share/tessdata",
        };
        for (String p : paths) {
            if (p != null && new java.io.File(p).exists()) {
                log.info("Tesseract tessdata: {}", p);
                return p;
            }
        }
        log.warn("No tessdata path found; defaulting to /usr/share/tesseract-ocr/5/tessdata");
        return "/usr/share/tesseract-ocr/5/tessdata";
    }
}
