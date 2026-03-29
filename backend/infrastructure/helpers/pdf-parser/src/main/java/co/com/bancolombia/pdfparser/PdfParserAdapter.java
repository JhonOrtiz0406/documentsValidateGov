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
            Tesseract tess = createTesseract(3);
            int pagesToScan = Math.min(totalPages, 6);

            LinkedHashMap<Integer, String> certPagePins = new LinkedHashMap<>();
            String firstLabeledPin = null;
            // Keep best OCR text per page for last-resort pass
            Map<Integer, String> pageTexts = new LinkedHashMap<>();

            for (int i = 0; i < pagesToScan; i++) {
                log.info("OCR page {}/{}", i + 1, pagesToScan);

                // Get 300-DPI text first for cert detection & logging
                String text300 = ocrPage(renderer, tess, i, 300);
                if (!text300.isBlank()) {
                    logPageText(i + 1, text300);
                    pageTexts.put(i + 1, text300);
                }

                // Multi-pass voting: extracts PIN from both 300 DPI and 600 DPI and
                // picks the most reliable reading to reduce single-pass digit errors.
                String pin = ocrPageWithVoting(renderer, tess, i, true);

                // If voting changed text (alternate PSM), update pageTexts
                // Re-fetch best available text for cert detection
                String bestText = text300;
                if (pin != null && text300.isBlank()) {
                    // Alternate PSM found something — get its text
                    String altText = ocrPageBestEffort(renderer, i, 300);
                    if (!altText.isBlank()) {
                        bestText = altText;
                        pageTexts.put(i + 1, altText);
                        if (text300.isBlank()) logPageText(i + 1, altText);
                    }
                }

                boolean isCert = !bestText.isBlank() && isCertificatePage(bestText);

                if (pin != null) {
                    log.info("OCR page {} voted PIN: {}", i + 1, pin);
                    if (isCert) certPagePins.put(i + 1, pin);
                    else if (firstLabeledPin == null) firstLabeledPin = pin;
                }
            }

            if (!certPagePins.isEmpty()) return buildResult(certPagePins);
            if (firstLabeledPin != null) return PinExtractionResult.single(firstLabeledPin);

            // Last resort: scan all collected OCR text; bypass cert-page gate since
            // OCR may have missed keywords on real certificate pages.
            log.info("OCR last-resort pass over {} pages of collected text", pageTexts.size());
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

    /**
     * Try multiple Tesseract PSM modes on a page. Returns the first text that
     * yields a PIN, or the longest non-empty text as a fallback.
     * PSM 3 = auto (best for mixed layouts)
     * PSM 6 = uniform block of text (good for forms/certificates)
     * PSM 4 = single column (good for documents with headers)
     * PSM 11 = sparse text (good when text is scattered on scanned images)
     */
    private String ocrPageBestEffort(PDFRenderer renderer, int idx, int dpi) {
        int[] psmModes = {3, 6, 4, 11};
        String longestText = "";
        for (int psm : psmModes) {
            try {
                Tesseract t = createTesseract(psm);
                String text = ocrPage(renderer, t, idx, dpi);
                if (!text.isBlank()) {
                    // If this PSM already yields a PIN, use it immediately
                    String pin = findWithLabeledPatterns(text, true);
                    if (pin == null) pin = findWithFallback(text);
                    if (pin != null) {
                        log.info("OCR page {} PSM {} at {}dpi found PIN: {}", idx + 1, psm, dpi, pin);
                        return text;
                    }
                    if (text.length() > longestText.length()) longestText = text;
                }
            } catch (Exception e) {
                log.warn("OCR PSM {} page {} at {}dpi failed: {}", psm, idx + 1, dpi, e.getMessage());
            }
        }
        return longestText;
    }

    /**
     * OCR with 3-pass voting: runs at 300, 400, and 600 DPI; collects PIN candidates
     * from each pass and selects the most plausible result.
     *
     * <ul>
     *   <li>Candidates that are OCR variants of each other (edit distance ≤ 2) are
     *       resolved by {@link #selectBestPin}: prefers pins in the expected SNR range
     *       (19-22 digits); among those, prefers the shortest (fewer insertions).</li>
     *   <li>If all DPI passes fail, escalates to alternate Tesseract PSM modes.</li>
     * </ul>
     */
    private String ocrPageWithVoting(PDFRenderer renderer, Tesseract tess,
                                     int idx, boolean isOcr) {
        String text300 = ocrPage(renderer, tess, idx, 300);
        String pin300  = findWithLabeledPatterns(text300, isOcr);
        if (pin300 == null) pin300 = findWithFallback(text300);

        // 400 DPI (no extra x2 scaling) — different interpolation path, different error
        String text400 = ocrPage(renderer, tess, idx, 400);
        String pin400  = findWithLabeledPatterns(text400, isOcr);
        if (pin400 == null) pin400 = findWithFallback(text400);

        String text600 = ocrPage(renderer, tess, idx, 600);
        String pin600  = findWithLabeledPatterns(text600, isOcr);
        if (pin600 == null) pin600 = findWithFallback(text600);

        log.info("OCR voting page {}: 300dpi={} | 400dpi={} | 600dpi={}", idx + 1, pin300, pin400, pin600);

        // Collect non-null candidates and select the best
        List<String> candidates = new ArrayList<>();
        if (pin300 != null) candidates.add(pin300);
        if (pin400 != null) candidates.add(pin400);
        if (pin600 != null) candidates.add(pin600);

        // If all passes failed, try alternate PSM modes before giving up
        if (candidates.isEmpty()) {
            log.info("OCR voting page {}: all DPI passes yielded no PIN, trying alternate PSMs", idx + 1);
            String altText = ocrPageBestEffort(renderer, idx, 300);
            if (!altText.isBlank()) {
                String altPin = findWithLabeledPatterns(altText, isOcr);
                if (altPin == null) altPin = findWithFallback(altText);
                if (altPin != null) {
                    log.info("OCR page {} alternate PSM found PIN: {}", idx + 1, altPin);
                }
                return altPin;
            }
            return null;
        }

        if (candidates.size() == 1) return candidates.get(0);

        // Deduplicate: if all candidates are OCR variants of the same number, pick best
        String best = selectBestPin(candidates);
        boolean allVariants = candidates.stream()
                .allMatch(p -> Math.abs(p.length() - best.length()) <= 2
                        && editDistance(p, best) <= 2);
        if (allVariants) {
            log.info("OCR voting page {}: candidates {} → best={}", idx + 1, candidates, best);
            return best;
        }

        // Genuinely different readings — prefer whichever is in the expected length range
        List<String> inRange = candidates.stream()
                .filter(p -> p.length() >= 19 && p.length() <= 22)
                .sorted(Comparator.comparingInt(String::length))
                .collect(java.util.stream.Collectors.toList());
        if (!inRange.isEmpty()) {
            log.info("OCR voting page {}: differing readings, picking in-range: {}", idx + 1, inRange.get(0));
            return inRange.get(0);
        }

        return best; // fallback: best by length heuristic
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
     *  When no candidate is in the ideal range, prefer the longest run (fewer deletions). */
    private String findWithFallback(String text) {
        String merged = mergeNewlineSplitDigits(text);
        Matcher m = FALLBACK.matcher(merged);
        String bestInRange = null;
        String bestAny = null;
        while (m.find()) {
            String c = m.group(1);
            if (c.length() >= 19 && c.length() <= 22) {
                if (bestInRange == null || c.length() < bestInRange.length())
                    bestInRange = c; // prefer shorter within range (fewer insertions)
            } else if (c.length() >= 15) {
                if (bestAny == null || c.length() > bestAny.length())
                    bestAny = c; // prefer longer outside range (fewer deletions)
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
        // OCR errors typically produce edit-distance ≤ 2 (1 wrong digit or 1 extra digit).
        // If so, pick the most reliable candidate instead of declaring a false conflict.
        String best = selectBestPin(unique);
        boolean allVariants = unique.stream()
                .allMatch(p -> Math.abs(p.length() - best.length()) <= 2
                        && editDistance(p, best) <= 2);

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

    // ── Tesseract setup ───────────────────────────────────────────────────────────

    /** Creates a Tesseract instance with the given PSM (page segmentation mode). */
    private Tesseract createTesseract(int psm) {
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
        t.setLanguage(lang);
        t.setPageSegMode(psm);
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
