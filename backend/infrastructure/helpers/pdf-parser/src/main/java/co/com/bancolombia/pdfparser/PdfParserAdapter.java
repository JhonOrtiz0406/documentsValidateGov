package co.com.bancolombia.pdfparser;

import co.com.bancolombia.model.document.PinExtractionResult;
import co.com.bancolombia.model.document.gateway.PdfParserGateway;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
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
    // Range widened to 35 interior chars to handle OCR output with many spaces.
    private static final String DB = "(\\d[\\d \\t]{8,35}\\d)";

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
        // C0 – OCR splits "No" into "N o" or "N°" is misread: "Pin N o :" / "P i n N ° :"
        Pattern.compile("(?i)P\\s*[iI1]\\s*[nN]\\s+[Nn]\\s*[°º]?\\s*[oO0]?\\s*[:\\-./]?\\s*" + DB),
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

    // Fallback: pure digit run, used after merging newlines (cert pages only in text mode)
    private static final Pattern FALLBACK = Pattern.compile("(\\d{15,25})");

    // OCR fallback: digit run allowing embedded spaces/tabs (OCR splits digits with spaces)
    // Captures e.g. "2302285 284729281146" → strip spaces → "2302285284729281146"
    private static final Pattern FALLBACK_OCR = Pattern.compile("(\\d[\\d \\t]{13,35}\\d)");

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

        // cert pages → primary conflict detection
        LinkedHashMap<Integer, String> certPagePins = new LinkedHashMap<>();
        // ALL pages with any labeled PIN (cert + non-cert) — fallback conflict detection
        // for mixed documents where cert-keyword detection may fail on some pages
        LinkedHashMap<Integer, String> allLabeledPagePins = new LinkedHashMap<>();

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
                allLabeledPagePins.put(page, pin); // always track
                if (isCert) {
                    certPagePins.put(page, pin);
                }
            } else if (isCert) {
                // Cert page but labeled pattern didn't match → try fallback digits
                pin = findWithFallback(text);
                if (pin != null) {
                    log.info("Page {} — fallback-digit PIN: {}", page, pin);
                    certPagePins.put(page, pin);
                    allLabeledPagePins.put(page, pin);
                } else {
                    log.warn("Page {} — cert page but no PIN extracted", page);
                }
            }
        }

        // Priority 1: cert pages (most reliable)
        if (!certPagePins.isEmpty()) {
            return buildResult(certPagePins);
        }
        // Priority 2: ALL labeled pages — catches conflicts on non-cert pages too
        // (e.g. a mixed doc containing two different certificates without cert keywords)
        if (!allLabeledPagePins.isEmpty()) {
            return buildResult(allLabeledPagePins);
        }
        return null;
    }

    // ── Strategy 2: Tesseract OCR ─────────────────────────────────────────────────

    private PinExtractionResult tryOcrExtraction(PDDocument doc, int totalPages) {
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            Tesseract tess = createTesseract(3);
            int pagesToScan = Math.min(totalPages, 15);

            LinkedHashMap<Integer, String> certPagePins = new LinkedHashMap<>();
            // All pages with any labeled PIN (cert or not) — used for conflict detection
            // when cert-keyword OCR fails but the PIN label is still readable.
            LinkedHashMap<Integer, String> allLabeledPagePins = new LinkedHashMap<>();
            // Keep best OCR text per page for last-resort pass
            Map<Integer, String> pageTexts = new LinkedHashMap<>();

            for (int i = 0; i < pagesToScan; i++) {
                log.info("OCR page {}/{}", i + 1, pagesToScan);

                // Pass 1: preprocessed 300 DPI (also used for cert-page detection)
                String text300 = ocrPage(renderer, tess, i, 300);
                if (!text300.isBlank()) {
                    logPageText(i + 1, text300);
                    pageTexts.put(i + 1, text300);
                }

                // Try PIN in pass-1 text first (reuse already-computed OCR)
                String pin = findWithLabeledPatterns(text300, true);
                if (pin == null) pin = findWithFallback(text300);

                // Pass 2 only if pass 1 found nothing: raw 300 DPI with PSM 6
                if (pin == null) {
                    Tesseract tess6 = createTesseract(6);
                    String textRaw = ocrPageRaw(renderer, tess6, i, 300);
                    if (!textRaw.isBlank()) {
                        if (text300.isBlank()) { logPageText(i + 1, textRaw); }
                        pageTexts.put(i + 1, textRaw);
                        pin = findWithLabeledPatterns(textRaw, true);
                        if (pin == null) pin = findWithFallback(textRaw);
                        if (pin != null) text300 = textRaw; // use for cert detection
                    }
                }

                // Pass 3: if still nothing, retry at 600 DPI (raw, PSM 3).
                // Scanned government docs embedded at ~150-180 DPI benefit from a
                // higher-resolution render: more pixels per character allow Tesseract
                // to resolve digit strokes that were too coarse at 300 DPI.
                if (pin == null) {
                    Tesseract tess600 = createTesseract(3);
                    String text600 = ocrPageRaw(renderer, tess600, i, 600);
                    if (!text600.isBlank()) {
                        log.info("Pass-3 (600 DPI) page {} produced {} chars", i + 1, text600.length());
                        if (text300.isBlank()) { logPageText(i + 1, text600); }
                        if (!pageTexts.containsKey(i + 1)) pageTexts.put(i + 1, text600);
                        pin = findWithLabeledPatterns(text600, true);
                        if (pin == null) pin = findWithFallback(text600);
                        if (pin != null) text300 = text600; // use for cert detection
                    }
                }

                // ── Rescue passes: only for pages where ALL prior passes returned blank ──
                // The Yoneida document embeds pages at ~85 DPI (720×960 px); rendering at
                // 300 DPI causes 3.5× interpolation artifacts. Rescue-E extracts the raw
                // embedded image directly from the PDF page resources — bypassing rendering
                // and avoiding double-scaling — and OCRs it at native resolution × 2.
                // Other rescues try alternative preprocessing for colored-background pages.
                boolean allPassesBlank = text300.isBlank(); // snapshot BEFORE rescues

                if (allPassesBlank && pin == null) {
                    // Rescue A: PSM 11 (sparse text) with preprocessed 300 DPI
                    String rText = ocrPageRescuePsm(renderer, i, 300, 11);
                    if (!rText.isBlank()) {
                        log.info("Rescue-A (PSM 11) page {} {} chars", i + 1, rText.length());
                        logPageText(i + 1, rText);
                        pageTexts.put(i + 1, rText); text300 = rText;
                        pin = findWithLabeledPatterns(rText, true);
                        if (pin == null) pin = findWithFallback(rText);
                    }
                }

                if (allPassesBlank && pin == null) {
                    // Rescue B: direct RGB color (Tesseract's own adaptive binarization)
                    String rText = ocrPageColor(renderer, i, 300);
                    if (!rText.isBlank()) {
                        log.info("Rescue-B (color) page {} {} chars", i + 1, rText.length());
                        if (text300.isBlank()) { logPageText(i + 1, rText); pageTexts.put(i + 1, rText); text300 = rText; }
                        pin = findWithLabeledPatterns(rText, true);
                        if (pin == null) pin = findWithFallback(rText);
                    }
                }

                if (allPassesBlank && pin == null) {
                    // Rescue C: brightened preprocessing (scale=2.0, offset=+60)
                    String rText = ocrPageBrightened(renderer, i, 300);
                    if (!rText.isBlank()) {
                        log.info("Rescue-C (bright) page {} {} chars", i + 1, rText.length());
                        if (text300.isBlank()) { logPageText(i + 1, rText); pageTexts.put(i + 1, rText); text300 = rText; }
                        pin = findWithLabeledPatterns(rText, true);
                        if (pin == null) pin = findWithFallback(rText);
                    }
                }

                if (allPassesBlank && pin == null) {
                    // Rescue D: brightened + PSM 6 at 400 DPI
                    String rText = ocrPageBrightenedPsm(renderer, i, 400, 6);
                    if (!rText.isBlank()) {
                        log.info("Rescue-D (bright PSM6 400dpi) page {} {} chars", i + 1, rText.length());
                        if (text300.isBlank()) { logPageText(i + 1, rText); pageTexts.put(i + 1, rText); text300 = rText; }
                        pin = findWithLabeledPatterns(rText, true);
                        if (pin == null) pin = findWithFallback(rText);
                    }
                }

                if (allPassesBlank && pin == null) {
                    // Rescue E: extract embedded image XObject directly from PDF page —
                    // avoids rendering artifacts from PDFBox scaling low-DPI source images.
                    String rText = ocrPageEmbeddedImage(doc, i);
                    if (!rText.isBlank()) {
                        log.info("Rescue-E (embedded img) page {} {} chars", i + 1, rText.length());
                        if (text300.isBlank()) { logPageText(i + 1, rText); pageTexts.put(i + 1, rText); text300 = rText; }
                        pin = findWithLabeledPatterns(rText, true);
                        if (pin == null) pin = findWithFallback(rText);
                    }
                }

                // Digit-only refinement: re-OCR with whitelist=0-9 to fix common digit
                // misreads (8→0, 2→7, etc.) that occur when Tesseract considers letters
                // as alternatives to ambiguous digit strokes.
                if (pin != null) {
                    pin = refineWithDigitOnly(renderer, doc, i, pin);
                }

                log.info("OCR page {}: PIN={}", i + 1, pin);

                boolean isCert = !text300.isBlank() && isCertificatePage(text300);

                if (pin != null) {
                    log.info("OCR page {} voted PIN: {}", i + 1, pin);
                    allLabeledPagePins.put(i + 1, pin); // always track
                    if (isCert) certPagePins.put(i + 1, pin);
                }
            }

            // Priority 1: cert pages (most reliable source)
            if (!certPagePins.isEmpty()) return buildResult(certPagePins);
            // Priority 2: ALL labeled pages — handles docs where cert keywords were
            // garbled by OCR but PIN labels are still readable (e.g. Yoneida scenario).
            if (!allLabeledPagePins.isEmpty()) return buildResult(allLabeledPagePins);

            // Last resort: scan all collected OCR text; bypass cert-page gate since
            // OCR may have missed keywords on real certificate pages.
            log.info("OCR last-resort: scanned {}/{} pages, got text on {} pages",
                    pagesToScan, totalPages, pageTexts.size());
            StringBuilder all = new StringBuilder();
            pageTexts.values().forEach(t -> all.append(t).append("\n"));
            if (all.length() == 0) {
                // Nothing from default tess — try best-effort multi-PSM on all pages
                for (int i = 0; i < pagesToScan; i++) {
                    String t = ocrPageBestEffort(renderer, i, 300);
                    all.append(t).append("\n");
                }
            }
            String allText = all.toString();
            String pin = findWithLabeledPatterns(allText, true);
            if (pin == null) pin = findWithFallback(allText); // no cert-page gate
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

    /** Rescue A: PSM-mode variant with preprocessed image. */
    private String ocrPageRescuePsm(PDFRenderer renderer, int idx, int dpi, int psm) {
        try {
            Tesseract t = createTesseract(psm);
            BufferedImage img = renderer.renderImageWithDPI(idx, dpi, ImageType.RGB);
            return t.doOCR(preprocessImage(img));
        } catch (Exception e) {
            log.warn("Rescue PSM{} failed page {} at {} DPI: {}", psm, idx + 1, dpi, e.getMessage());
            return "";
        }
    }

    /** Rescue B: pass color (RGB) image directly — Tesseract handles its own binarization. */
    private String ocrPageColor(PDFRenderer renderer, int idx, int dpi) {
        try {
            Tesseract t = createTesseract(3);
            BufferedImage img = renderer.renderImageWithDPI(idx, dpi, ImageType.RGB);
            return t.doOCR(img); // no preprocessing — let Tesseract do adaptive binarization
        } catch (Exception e) {
            log.warn("Rescue color failed page {} at {} DPI: {}", idx + 1, dpi, e.getMessage());
            return "";
        }
    }

    /** Rescue C: brightened grayscale — scale=2.0, offset=+60 to surface faint text. */
    private String ocrPageBrightened(PDFRenderer renderer, int idx, int dpi) {
        try {
            Tesseract t = createTesseract(3);
            BufferedImage img = renderer.renderImageWithDPI(idx, dpi, ImageType.RGB);
            return t.doOCR(preprocessBrightened(img));
        } catch (Exception e) {
            log.warn("Rescue bright failed page {} at {} DPI: {}", idx + 1, dpi, e.getMessage());
            return "";
        }
    }

    /** Rescue D: brightened + specific PSM at custom DPI. */
    private String ocrPageBrightenedPsm(PDFRenderer renderer, int idx, int dpi, int psm) {
        try {
            Tesseract t = createTesseract(psm);
            BufferedImage img = renderer.renderImageWithDPI(idx, dpi, ImageType.RGB);
            return t.doOCR(preprocessBrightened(img));
        } catch (Exception e) {
            log.warn("Rescue bright PSM{} failed page {} at {} DPI: {}", psm, idx + 1, dpi, e.getMessage());
            return "";
        }
    }

    /**
     * Rescue E: extract the largest embedded image XObject from the PDF page directly.
     *
     * <p>Pages that embed scanned certificate images at ~85 DPI (e.g. 720×960 px) lose
     * quality when PDFBox renders them at 300 DPI (3.5× interpolation). Extracting the
     * raw image bypasses that scaling, giving Tesseract the native pixel data with only
     * a clean 2× upscale applied by us.</p>
     *
     * <p>Multiple XObjects per page are handled by picking the largest by pixel area,
     * which is almost always the main certificate body image.</p>
     */
    private String ocrPageEmbeddedImage(PDDocument doc, int pageIdx) {
        try {
            PDPage page = doc.getPage(pageIdx);
            PDResources resources = page.getResources();
            BufferedImage largest = null;
            int largestArea = 0;

            for (COSName name : resources.getXObjectNames()) {
                try {
                    PDXObject xObj = resources.getXObject(name);
                    if (xObj instanceof PDImageXObject imgXObj) {
                        BufferedImage img = imgXObj.getImage();
                        int area = img.getWidth() * img.getHeight();
                        if (area > largestArea) {
                            largestArea = area;
                            largest = img;
                        }
                    }
                } catch (Exception inner) {
                    log.debug("XObject {} page {}: {}", name, pageIdx + 1, inner.getMessage());
                }
            }

            if (largest == null) {
                log.info("Rescue-E page {}: no embedded image XObject found", pageIdx + 1);
                return "";
            }

            log.info("Rescue-E page {}: embedded image {}×{} px", pageIdx + 1, largest.getWidth(), largest.getHeight());
            BufferedImage scaled = scaleImage2x(largest);

            // Try PSM 3 first (auto), then PSM 6 (single block), then PSM 11 (sparse)
            for (int psm : new int[]{3, 6, 11}) {
                Tesseract t = createTesseract(psm);
                String text = t.doOCR(scaled);
                if (!text.isBlank()) {
                    String pin = findWithLabeledPatterns(text, true);
                    if (pin == null) pin = findWithFallback(text);
                    if (pin != null) {
                        log.info("Rescue-E PSM{} page {} found PIN: {}", psm, pageIdx + 1, pin);
                        return text;
                    }
                    // Keep trying other PSMs — maybe one produces better text
                    log.debug("Rescue-E PSM{} page {}: {} chars, no PIN", psm, pageIdx + 1, text.length());
                }
            }

            // Last attempt: preprocessed (contrast boost) on the extracted image
            Tesseract tPre = createTesseract(3);
            String textPre = tPre.doOCR(preprocessImage(largest));
            if (!textPre.isBlank()) return textPre;

            return "";
        } catch (Exception e) {
            log.warn("Rescue-E (embedded image) page {}: {}", pageIdx + 1, e.getMessage());
            return "";
        }
    }

    /** Scale image 2× with bicubic interpolation (same as preprocessImage's step 1). */
    private BufferedImage scaleImage2x(BufferedImage src) {
        int w = src.getWidth() * 2, h = src.getHeight() * 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** OCR with raw image — no 2x scaling, no contrast boost; just grayscale. */
    private String ocrPageRaw(PDFRenderer renderer, Tesseract tess, int idx, int dpi) {
        try {
            BufferedImage img = renderer.renderImageWithDPI(idx, dpi, ImageType.GRAY);
            return tess.doOCR(img);
        } catch (Exception e) {
            log.warn("OCR-raw failed page {} at {} DPI: {}", idx + 1, dpi, e.getMessage());
            return "";
        }
    }

    /**
     * Last-resort OCR: tries PSM 4 and PSM 11 with preprocessing, then raw.
     * Called at most once per document (not per page), so 3 extra passes is acceptable.
     */
    private String ocrPageBestEffort(PDFRenderer renderer, int idx, int dpi) {
        String longestText = "";
        for (int psm : new int[]{4, 11}) {
            try {
                Tesseract t = createTesseract(psm);
                String text = ocrPage(renderer, t, idx, dpi);
                if (!text.isBlank()) {
                    String pin = findWithLabeledPatterns(text, true);
                    if (pin == null) pin = findWithFallback(text);
                    if (pin != null) {
                        log.info("BestEffort page {} psm={} {}dpi found PIN: {}", idx + 1, psm, dpi, pin);
                        return text;
                    }
                    if (text.length() > longestText.length()) longestText = text;
                }
            } catch (Exception e) {
                log.warn("BestEffort psm={} page {} failed: {}", psm, idx + 1, e.getMessage());
            }
        }
        // Raw pass as final attempt
        try {
            Tesseract tRaw = createTesseract(3);
            String text = ocrPageRaw(renderer, tRaw, idx, dpi);
            if (!text.isBlank()) {
                String pin = findWithLabeledPatterns(text, true);
                if (pin == null) pin = findWithFallback(text);
                if (pin != null) {
                    log.info("BestEffort page {} raw {}dpi found PIN: {}", idx + 1, dpi, pin);
                    return text;
                }
                if (text.length() > longestText.length()) longestText = text;
            }
        } catch (Exception e) {
            log.warn("BestEffort raw page {} failed: {}", idx + 1, e.getMessage());
        }
        return longestText;
    }

    // ── Digit-only refinement ─────────────────────────────────────────────────────

    /**
     * Re-OCR the page with a digit-only character whitelist to correct common digit
     * misreads (e.g. 8→0, 2→7) that occur when Tesseract considers letters as valid
     * alternatives to ambiguous digit strokes.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Render the page at 300 DPI and run Tesseract with {@code tessedit_char_whitelist=0123456789}.</li>
     *   <li>If the page has a low-DPI embedded image (Rescue-E scenario), also try the raw XObject.</li>
     *   <li>Among all candidates of the same length, pick the one with minimum edit distance
     *       to the input candidate.</li>
     *   <li>Accept the refined PIN only if it is within 2 edits — meaning at most 2 digits
     *       were corrected. This guards against picking up a completely different number
     *       (e.g. page reference, date) by accident.</li>
     * </ol>
     * </p>
     */
    private String refineWithDigitOnly(PDFRenderer renderer, PDDocument doc, int pageIdx, String candidatePin) {
        int expectedLen = candidatePin.length();
        String best = null;
        int bestDist = Integer.MAX_VALUE;

        // Attempt 1: standard 300 DPI render with both PSM 6 and PSM 3
        try {
            BufferedImage img = renderer.renderImageWithDPI(pageIdx, 300, ImageType.RGB);
            for (int psm : new int[]{6, 3}) {
                try {
                    Tesseract t = createTesseract(psm);
                    t.setVariable("tessedit_char_whitelist", "0123456789");
                    String text = t.doOCR(preprocessImage(img));
                    String candidate = longestDigitRunOfLength(text, expectedLen);
                    if (candidate != null) {
                        int d = editDistance(candidate, candidatePin);
                        if (d < bestDist) { bestDist = d; best = candidate; }
                    }
                } catch (Exception e) {
                    log.debug("Digit-only PSM{} page {} failed: {}", psm, pageIdx + 1, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Digit-only render page {} failed: {}", pageIdx + 1, e.getMessage());
        }

        // Attempt 2: raw embedded image XObject (avoids PDFBox scaling artifacts on low-DPI scans)
        try {
            PDPage page = doc.getPage(pageIdx);
            PDResources res = page.getResources();
            BufferedImage largest = null;
            int largestArea = 0;
            for (COSName name : res.getXObjectNames()) {
                try {
                    PDXObject xObj = res.getXObject(name);
                    if (xObj instanceof PDImageXObject imgXObj) {
                        BufferedImage img = imgXObj.getImage();
                        int area = img.getWidth() * img.getHeight();
                        if (area > largestArea) { largestArea = area; largest = img; }
                    }
                } catch (Exception inner) {
                    log.debug("XObject {} digit-only page {}: {}", name, pageIdx + 1, inner.getMessage());
                }
            }
            if (largest != null) {
                BufferedImage scaled = scaleImage2x(largest);
                for (int psm : new int[]{6, 3}) {
                    try {
                        Tesseract t = createTesseract(psm);
                        t.setVariable("tessedit_char_whitelist", "0123456789");
                        String text = t.doOCR(scaled);
                        String candidate = longestDigitRunOfLength(text, expectedLen);
                        if (candidate != null) {
                            int d = editDistance(candidate, candidatePin);
                            if (d < bestDist) { bestDist = d; best = candidate; }
                        }
                    } catch (Exception e) {
                        log.debug("Digit-only embedded PSM{} page {} failed: {}", psm, pageIdx + 1, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Digit-only embedded image page {} failed: {}", pageIdx + 1, e.getMessage());
        }

        if (best != null && bestDist > 0 && bestDist <= 2) {
            log.info("Digit-only refinement page {}: {} → {} (edit dist={})",
                    pageIdx + 1, candidatePin, best, bestDist);
            return best;
        }
        log.debug("Digit-only refinement page {}: no improvement (bestDist={}), keeping {}",
                pageIdx + 1, bestDist, candidatePin);
        return candidatePin;
    }

    /**
     * Among all digit runs in {@code text} (allowing embedded spaces/tabs from OCR),
     * return the one whose stripped length equals {@code targetLen}, preferring the
     * first occurrence if there are ties. Returns null if no such run exists.
     */
    private String longestDigitRunOfLength(String text, int targetLen) {
        if (text == null || text.isBlank()) return null;
        Matcher m = FALLBACK_OCR.matcher(text);
        String found = null;
        while (m.find()) {
            String raw = m.group(1);
            String clean = raw.replaceAll("[ \\t]+", "");
            if (clean.matches("\\d+") && clean.length() == targetLen) {
                if (found == null) found = clean; // keep first (left-to-right = natural reading order)
            }
        }
        return found;
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

    /** Fallback: find any 15-25 digit run; prefer 19-22 digits (typical SNR PIN).
     *  When no candidate is in the ideal range, prefer the longest run (fewer deletions).
     *  For OCR text, also tries space-tolerant matching (OCR splits digits with spaces). */
    private String findWithFallback(String text) {
        String merged = mergeNewlineSplitDigits(text);

        String best = bestMatchInText(merged, FALLBACK, false);
        if (best != null) return best;

        // OCR may have inserted spaces inside digit sequences — try space-tolerant pattern
        best = bestMatchInText(merged, FALLBACK_OCR, true);
        return best;
    }

    private String bestMatchInText(String text, Pattern pat, boolean stripSpaces) {
        Matcher m = pat.matcher(text);
        String bestInRange = null;
        String bestAny = null;
        while (m.find()) {
            String raw = m.group(1);
            String c = stripSpaces ? raw.replaceAll("[ \\t]+", "") : raw;
            if (!c.matches("\\d+")) continue; // must be pure digits after strip
            if (c.length() >= 19 && c.length() <= 22) {
                if (bestInRange == null || c.length() < bestInRange.length())
                    bestInRange = c;
            } else if (c.length() >= 15) {
                if (bestAny == null || c.length() > bestAny.length())
                    bestAny = c;
            }
        }
        if (bestInRange != null) {
            log.info("Fallback PIN ({} digits): {}", bestInRange.length(), bestInRange);
            return bestInRange;
        }
        if (bestAny != null) log.info("Fallback PIN (backup {} digits): {}", bestAny.length(), bestAny);
        return bestAny;
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

        // Check whether all "different" PINs are just OCR variants of the same number.
        // Rules:
        //  • Same length  → allow at most 1 substitution (edit-dist ≤ 1).
        //    Two substitutions on the same-length number (e.g. "…23" vs "…32") almost
        //    always means a genuine PIN difference, not an OCR glitch.
        //  • Diff length  → allow up to 2 edits (insertion/deletion ± 1 substitution).
        //    OCR commonly inserts or deletes a single digit on long number runs.
        String best = selectBestPin(unique);
        boolean allVariants = unique.stream()
                .allMatch(p -> {
                    int lenDiff = Math.abs(p.length() - best.length());
                    int ed      = editDistance(p, best);
                    return lenDiff == 0 ? ed <= 1 : (lenDiff <= 2 && ed <= 2);
                });

        if (allVariants) {
            log.info("PINs {} look like OCR variants of the same number → selecting: {}",
                    unique, best);
            return PinExtractionResult.single(best);
        }

        log.warn("GENUINE CONFLICT: different PINs found across pages: {}", unique);
        return PinExtractionResult.conflict(unique);
    }

    /**
     * Among OCR candidates, pick the most plausible PIN.
     *
     * Strategy:
     * 1. Prefer any candidate whose length falls in the expected SNR PIN range (19-22).
     *    Within that range, prefer the shortest (fewer OCR insertions).
     * 2. If none is in-range, prefer the longest (fewer OCR deletions).
     *
     * This handles both error directions:
     *  - OCR inserts a digit: candidates are e.g. 19 and 20 → both in range → pick 19 ✓
     *  - OCR deletes a digit: candidates are e.g. 18 and 19 → only 19 in range → pick 19 ✓
     */
    private String selectBestPin(List<String> pins) {
        List<String> inRange = pins.stream()
                .filter(p -> p.length() >= 19 && p.length() <= 22)
                .sorted(Comparator.comparingInt(String::length))
                .collect(java.util.stream.Collectors.toList());
        if (!inRange.isEmpty()) return inRange.get(0);

        // No candidate in ideal range — prefer longer (deletion errors are common)
        return pins.stream()
                .filter(p -> p.length() >= 15)
                .max(Comparator.comparingInt(String::length))
                .orElse(pins.get(0));
    }

    /** Exposed for unit tests. */
    int editDistancePublic(String a, String b) { return editDistance(a, b); }

    /** Standard Levenshtein distance (space-optimised two-row DP). */
    private int editDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                curr[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
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

    /**
     * Prepares a page image for Tesseract OCR:
     *
     * <ol>
     *   <li>Scale up 2× with bicubic interpolation — larger glyphs are easier to
     *       classify correctly, especially for similar digits (5/6/8, 0/6, 1/7).</li>
     *   <li>Convert to grayscale — Tesseract performs its own Otsu binarisation
     *       internally, which is adaptive and outperforms a fixed global threshold.</li>
     * </ol>
     *
     * We deliberately do NOT apply a hard binary threshold here because Java's
     * {@code TYPE_BYTE_BINARY} uses a single global cutoff that can incorrectly
     * merge similar-looking digits on low-contrast documents.
     */
    private BufferedImage preprocessImage(BufferedImage src) {
        // Step 1 — scale up 2× for better per-glyph resolution
        int sw = src.getWidth() * 2;
        int sh = src.getHeight() * 2;
        BufferedImage scaled = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D gs = scaled.createGraphics();
        gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        gs.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        gs.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        gs.drawImage(src, 0, 0, sw, sh, null);
        gs.dispose();

        // Step 2 — convert to grayscale; let Tesseract do adaptive binarisation
        BufferedImage gray = new BufferedImage(sw, sh, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gg = gray.createGraphics();
        gg.drawImage(scaled, 0, 0, null);
        gg.dispose();

        // Step 3 — mild contrast boost (scale=1.3, offset=-20) to sharpen faded scans
        // without clamping valid digit strokes. RescaleOp clamps to [0,255] automatically.
        RescaleOp contrast = new RescaleOp(1.3f, -20f, null);
        return contrast.filter(gray, gray);
    }

    /**
     * Alternative preprocessing for dark/colored-background pages (Rescue C/D).
     * Uses aggressive brightness boost (scale=2.0, offset=+60) instead of darkening,
     * which can surface text that becomes invisible with the standard contrast-boost.
     * Intended only for pages where standard preprocessing yields empty OCR output.
     */
    private BufferedImage preprocessBrightened(BufferedImage src) {
        // Scale 2× (same as standard)
        int sw = src.getWidth() * 2;
        int sh = src.getHeight() * 2;
        BufferedImage scaled = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D gs = scaled.createGraphics();
        gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        gs.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        gs.drawImage(src, 0, 0, sw, sh, null);
        gs.dispose();

        // Convert to grayscale
        BufferedImage gray = new BufferedImage(sw, sh, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gg = gray.createGraphics();
        gg.drawImage(scaled, 0, 0, null);
        gg.dispose();

        // Aggressive brightness boost — lifts dark-background pages so text becomes
        // legible for Tesseract's internal Otsu binarization.
        RescaleOp bright = new RescaleOp(2.0f, 60f, null);
        return bright.filter(gray, gray);
    }

    // ── Tesseract setup ───────────────────────────────────────────────────────────

    /** Creates a Tesseract instance with the given PSM and OEM. */
    private Tesseract createTesseract(int psm, int oem) {
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
        // Use spa+eng combo: catches Spanish labels while eng handles digits more reliably
        boolean hasSpa = new java.io.File(dataPath, "spa.traineddata").exists();
        boolean hasEng = new java.io.File(dataPath, "eng.traineddata").exists();
        String lang = (hasSpa && hasEng) ? "spa+eng" : hasSpa ? "spa" : "eng";
        log.debug("Tesseract psm={} oem={} lang={}", psm, oem, lang);
        t.setLanguage(lang);
        t.setPageSegMode(psm);
        t.setOcrEngineMode(oem);
        // Override Tesseract's DPI auto-detection ("Estimating resolution as 1XX").
        // Low DPI estimates cause incorrect character-size normalization on scanned
        // government documents, leading to PIN=null even when text is readable.
        t.setVariable("user_defined_dpi", "300");
        return t;
    }

    /** Convenience wrapper with default OEM 1 (LSTM). */
    private Tesseract createTesseract(int psm) {
        return createTesseract(psm, 1);
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
