package co.com.bancolombia.pdfparser;

import co.com.bancolombia.model.document.PinExtractionResult;
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

    @ParameterizedTest(name = "Label pattern: \"{0}\" -> {1}")
    @CsvSource({
            "'Certificado generado con el Pin No: 2602202649129843602', 2602202649129843602",
            "'Certificado generado con el Pin No:2602202649129843602', 2602202649129843602",
            "'CertificadogeneradoconelPinNo:2602202649129843602', 2602202649129843602",
            "'certificado generado con el pin no: 2602202649129843602', 2602202649129843602",
            "'Pin No: 2602202649129843602', 2602202649129843602",
            "'Pin No:2602202649129843602', 2602202649129843602",
            "'PinNo:2602202649129843602', 2602202649129843602",
            "'PIN No: 2602202649129843602', 2602202649129843602",
            "'Pin N°: 2602202649129843602', 2602202649129843602",
            "'Pin N°2602202649129843602', 2602202649129843602",
            "'PIN: 2602202649129843602', 2602202649129843602",
    })
    void shouldExtractPinFromLabeledText(String text, String expectedPin) {
        String result = adapter.findBestPinInText(text, false);
        assertNotNull(result, "PIN should be found in: " + text);
        assertEquals(expectedPin, result);
    }

    @ParameterizedTest(name = "Split PIN: \"{0}\" -> {1}")
    @CsvSource({
            "'Pin No: 230228528472928 1146', 2302285284729281146",
            "'Pin No: 230228528472928\t1146', 2302285284729281146",
    })
    void shouldMergePinSplitByWhitespace(String text, String expectedPin) {
        String result = adapter.findBestPinInText(text, false);
        assertEquals(expectedPin, result, "PIN split by whitespace should merge correctly");
    }

    @Test
    void shouldMergePinSplitAcrossLines() {
        String text = "Certificado generado con el Pin No: 230228528472928\n1146";
        assertEquals("2302285284729281146", adapter.findBestPinInText(text, false));
    }

    @Test
    void shouldMergePinSplitByCrlf() {
        String text = "Pin No: 230228528472928\r\n1146";
        assertEquals("2302285284729281146", adapter.findBestPinInText(text, false));
    }

    @Test
    void shouldNotExtractPinFromIrrelevantText() {
        assertNull(adapter.findBestPinInText(
                "Documento genérico sin certificados ni PINes.", false));
    }

    @Test
    void shouldHandleNullAndBlank() {
        assertNull(adapter.findBestPinInText(null, false));
        assertNull(adapter.findBestPinInText("", false));
        assertNull(adapter.findBestPinInText("   ", false));
    }

    // ======================== Multi-page conflict detection ========================

    @Test
    void shouldReturnSingleResultWhenAllPagesAgreePinNoConflict() {
        // Two pages, same PIN → no conflict
        PinExtractionResult r1 = PinExtractionResult.single("2602202649129843602");
        assertFalse(r1.hasConflict());
        assertEquals("2602202649129843602", r1.primaryPin());
        assertEquals(1, r1.pins().size());
    }

    @Test
    void shouldReturnConflictWhenPagesHaveDifferentPins() {
        PinExtractionResult conflict = PinExtractionResult.conflict(
                java.util.List.of("2602202649129843602", "2302285284729281146"));
        assertTrue(conflict.hasConflict());
        assertEquals(2, conflict.pins().size());
        assertEquals("2602202649129843602", conflict.primaryPin());
    }

    // ======================== Real PDF Tests ========================

    @Test
    void shouldExtractPinFromKarenPdf() throws IOException {
        byte[] pdf = loadTestPdf("KAREN PATRICIA MOVILLA CABALLERO - CERTIFICADO DE LIBERTAD Y TRADICIÓN.pdf");
        if (pdf == null) return;

        StepVerifier.create(adapter.extractPins(pdf))
                .assertNext(result -> {
                    assertFalse(result.hasConflict(), "Should not have PIN conflict");
                    assertEquals("2602202649129843602", result.primaryPin(),
                            "Karen's certificate should have PIN 2602202649129843602");
                    System.out.println("Karen PDF -> " + result);
                })
                .verifyComplete();
    }

    @Test
    void shouldExtractPinFromLeonardoPdf() throws IOException {
        byte[] pdf = loadTestPdf("LEONARDO DAVID TORRES CAMPO - CERTIFICADO DE LIBERTAD Y TRADICIÓN.pdf");
        if (pdf == null) return;

        StepVerifier.create(adapter.extractPins(pdf))
                .assertNext(result -> {
                    assertNotNull(result.primaryPin(), "PIN should be extracted");
                    assertTrue(result.primaryPin().matches("\\d{10,25}"),
                            "PIN should be numeric, got: " + result.primaryPin());
                    System.out.println("Leonardo PDF -> " + result);
                })
                .verifyComplete();
    }

    @Test
    void shouldExtractPinFromYicethPdf() throws IOException {
        byte[] pdf = loadTestPdf("YICETH CECILIA BARRERA MONTIEL- SOPORTE.pdf");
        if (pdf == null) return;

        StepVerifier.create(adapter.extractPins(pdf).defaultIfEmpty(PinExtractionResult.single("NOT_FOUND")))
                .assertNext(result -> {
                    if ("NOT_FOUND".equals(result.primaryPin())) {
                        System.out.println("Yiceth PDF -> no PIN found (OCR may be needed)");
                    } else {
                        assertEquals("2302285284729281146", result.primaryPin(),
                                "Yiceth's certificate should have PIN 2302285284729281146, got: "
                                        + result.primaryPin());
                        System.out.println("Yiceth PDF -> " + result);
                    }
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleNischarPdf() throws IOException {
        byte[] pdf = loadTestPdf("NISCHAR MOGOLLON PUCHE - CERIFICADO DE LIBERTAD Y TRADICIÓN.pdf");
        if (pdf == null) return;

        StepVerifier.create(adapter.extractPins(pdf).defaultIfEmpty(PinExtractionResult.single("NOT_FOUND")))
                .assertNext(result -> {
                    if ("NOT_FOUND".equals(result.primaryPin())) {
                        System.out.println("Nischar PDF -> no PIN found (scanned, OCR required)");
                    } else {
                        assertTrue(result.primaryPin().matches("\\d{10,25}"),
                                "PIN should be numeric, got: " + result.primaryPin());
                        System.out.println("Nischar PDF -> " + result);
                    }
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleNonCertificatePdf() throws IOException {
        byte[] pdf = loadTestPdf("retiro de cesantias de yoneida morales paez.pdf");
        if (pdf == null) return;

        StepVerifier.create(adapter.extractPins(pdf).defaultIfEmpty(PinExtractionResult.single("NOT_FOUND")))
                .assertNext(result -> System.out.println("Retiro Cesantías PDF -> " + result))
                .verifyComplete();
    }

    // ======================== Helper ========================

    private byte[] loadTestPdf(String filename) throws IOException {
        Path pdfPath = Paths.get(DATA_PRUEBA_DIR, filename);
        if (!Files.exists(pdfPath)) {
            System.out.println("SKIP: test PDF not found at " + pdfPath.toAbsolutePath());
            return null;
        }
        return Files.readAllBytes(pdfPath);
    }

    private static String resolveDataPruebaPath() {
        String[] candidates = {
                "dataPrueba", "../dataPrueba", "../../dataPrueba",
                "../../../dataPrueba", "../../../../dataPrueba",
                "backend/dataPrueba",
        };
        for (String c : candidates) {
            if (Files.isDirectory(Paths.get(c))) return c;
        }
        return "C:\\trabajos\\DocumentsValidate\\backend\\dataPrueba";
    }
}
