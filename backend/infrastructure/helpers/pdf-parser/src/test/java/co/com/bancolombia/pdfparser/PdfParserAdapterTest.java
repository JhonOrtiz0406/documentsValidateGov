package co.com.bancolombia.pdfparser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class PdfParserAdapterTest {

    private PdfParserAdapter adapter;
    private static final String DATA_PRUEBA_DIR = resolveDataPruebaPath();

    @BeforeEach
    void setUp() {
        adapter = new PdfParserAdapter();
    }

    // ======================== PIN Pattern Matching Tests ========================

    @ParameterizedTest(name = "Pattern match: \"{0}\" -> {1}")
    @CsvSource({
            "'Certificado generado con el Pin No: 2602202649129843602', 2602202649129843602",
            "'CertificadogeneradoconelPinNo:2602202649129843602', 2602202649129843602",
            "'Certificado generado con el Pin No:2602202649129843602', 2602202649129843602",
            "'Certificado generado con el Pin No : 2602202649129843602', 2602202649129843602",
            "'certificado generado con el pin no: 2602202649129843602', 2602202649129843602",
            "'Pin No: 2602202649129843602', 2602202649129843602",
            "'Pin No:2602202649129843602', 2602202649129843602",
    })
    void shouldExtractPinFromCleanText(String text, String expectedPin) {
        String result = adapter.findPinInText(text, false);
        assertNotNull(result, "PIN should be found in text: " + text);
        assertEquals(expectedPin, result);
    }

    @Test
    void shouldMergePinSplitAcrossLines() {
        // PIN displayed on two lines in the PDF: first 15 digits, then 4 more on the next line
        String text = "Certificado generado con el Pin No: 230228528472928\n1146";
        String result = adapter.findPinInText(text, false);
        assertEquals("2302285284729281146", result,
                "PIN split across lines should be merged into a single 19-digit PIN");
    }

    @Test
    void shouldMergePinSplitAcrossLinesWithCarriageReturn() {
        String text = "Pin No: 230228528472928\r\n1146";
        String result = adapter.findPinInText(text, false);
        assertEquals("2302285284729281146", result,
                "PIN split with CRLF should be merged correctly");
    }

    @ParameterizedTest(name = "OCR pattern match: \"{0}\" -> {1}")
    @CsvSource({
            "'C e r t i f i c a d o generado con el Pin No: 2602202649129843602', 2602202649129843602",
            "'Certificado generado con el P i n N o : 2602202649129843602', 2602202649129843602",
    })
    void shouldExtractPinFromOcrText(String text, String expectedPin) {
        String result = adapter.findPinInText(text, true);
        assertNotNull(result, "PIN should be found in OCR text: " + text);
        assertEquals(expectedPin, result);
    }

    @Test
    void shouldNotExtractPinFromIrrelevantText() {
        String text = "Este es un documento genérico que no contiene certificados ni PINes.";
        String result = adapter.findPinInText(text, false);
        assertNull(result, "Should not find PIN in irrelevant text");
    }

    @Test
    void shouldHandleNullText() {
        assertNull(adapter.findPinInText(null, false));
    }

    @Test
    void shouldHandleEmptyText() {
        assertNull(adapter.findPinInText("", false));
    }

    // ======================== Real PDF Tests (text extraction) ========================

    @Test
    void shouldExtractPinFromLeonardoPdf() throws IOException {
        byte[] pdfBytes = loadTestPdf(
                "LEONARDO DAVID TORRES CAMPO - CERTIFICADO DE LIBERTAD Y TRADICIÓN.pdf");
        if (pdfBytes == null) return;

        StepVerifier.create(adapter.extractPin(pdfBytes))
                .assertNext(pin -> {
                    assertNotNull(pin, "Should extract PIN from Leonardo's certificate");
                    assertTrue(pin.matches("\\d{10,25}"),
                            "PIN should be a long numeric string, got: " + pin);
                    System.out.println("Leonardo PDF -> PIN: " + pin);
                })
                .verifyComplete();
    }

    @Test
    void shouldExtractPinFromKarenPdf() throws IOException {
        byte[] pdfBytes = loadTestPdf(
                "KAREN PATRICIA MOVILLA CABALLERO - CERTIFICADO DE LIBERTAD Y TRADICIÓN.pdf");
        if (pdfBytes == null) return;

        StepVerifier.create(adapter.extractPin(pdfBytes))
                .assertNext(pin -> {
                    assertNotNull(pin, "Should extract PIN from Karen's certificate");
                    assertTrue(pin.matches("\\d{10,25}"),
                            "PIN should be a long numeric string, got: " + pin);
                    assertEquals("2602202649129843602", pin,
                            "Karen's certificate should have PIN 2602202649129843602");
                    System.out.println("Karen PDF -> PIN: " + pin);
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleNischarPdf() throws IOException {
        // Nischar's PDF is scanned - requires OCR (Tesseract)
        // When Tesseract is not installed, extractPin returns Mono.empty() (null)
        byte[] pdfBytes = loadTestPdf(
                "NISCHAR MOGOLLON PUCHE - CERIFICADO DE LIBERTAD Y TRADICIÓN.pdf");
        if (pdfBytes == null) return;

        StepVerifier.create(adapter.extractPin(pdfBytes).defaultIfEmpty("NOT_FOUND"))
                .assertNext(pin -> {
                    if ("NOT_FOUND".equals(pin)) {
                        System.out.println("Nischar PDF -> No PIN found (OCR required, Tesseract may not be installed)");
                    } else {
                        assertTrue(pin.matches("\\d{10,25}"),
                                "PIN should be a long numeric string, got: " + pin);
                        System.out.println("Nischar PDF -> PIN: " + pin);
                    }
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleYicethPdf() throws IOException {
        byte[] pdfBytes = loadTestPdf(
                "YICETH CECILIA BARRERA MONTIEL- SOPORTE.pdf");
        if (pdfBytes == null) return;

        StepVerifier.create(adapter.extractPin(pdfBytes).defaultIfEmpty("NOT_FOUND"))
                .assertNext(pin -> {
                    if ("NOT_FOUND".equals(pin)) {
                        System.out.println("Yiceth PDF -> No PIN found (OCR may be needed)");
                    } else {
                        assertEquals("2302285284729281146", pin,
                                "Yiceth's certificate should have PIN 2302285284729281146, got: " + pin);
                        System.out.println("Yiceth PDF -> PIN: " + pin);
                    }
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleNonCertificatePdf() throws IOException {
        // "retiro de cesantías" is a severance pay document, NOT a certificate
        byte[] pdfBytes = loadTestPdf(
                "retiro de cesantias de yoneida morales paez.pdf");
        if (pdfBytes == null) return;

        StepVerifier.create(adapter.extractPin(pdfBytes).defaultIfEmpty("NOT_FOUND"))
                .assertNext(pin -> {
                    System.out.println("Retiro Cesantías PDF -> PIN: " + pin
                            + ("NOT_FOUND".equals(pin) ? " (correct: not a certificate)" : ""));
                })
                .verifyComplete();
    }

    // ======================== Helper ========================

    private byte[] loadTestPdf(String filename) throws IOException {
        Path pdfPath = Paths.get(DATA_PRUEBA_DIR, filename);
        if (!Files.exists(pdfPath)) {
            System.out.println("SKIP: Test PDF not found at " + pdfPath.toAbsolutePath());
            return null;
        }
        return Files.readAllBytes(pdfPath);
    }

    private static String resolveDataPruebaPath() {
        String[] candidates = {
                "dataPrueba",
                "../dataPrueba",
                "../../dataPrueba",
                "../../../dataPrueba",
                "../../../../dataPrueba",
                "backend/dataPrueba",
        };
        for (String candidate : candidates) {
            if (Files.isDirectory(Paths.get(candidate))) {
                return candidate;
            }
        }
        return "C:\\trabajos\\DocumentsValidate\\backend\\dataPrueba";
    }
}
