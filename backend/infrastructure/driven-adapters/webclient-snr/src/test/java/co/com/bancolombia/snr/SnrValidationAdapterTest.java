package co.com.bancolombia.snr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SnrValidationAdapterTest {

    private SnrValidationAdapter adapter;

    @BeforeEach
    void setUp() {
        // We only test the response parsing logic, not actual HTTP calls
        adapter = new SnrValidationAdapter(null, "http://dummy", 5);
    }

    @Test
    void shouldParseValidResponse() {
        String response = "<partial-response>" +
                "<update id=\"javax.faces.ViewRoot\">" +
                "Informacion 2602202649129843602 " +
                "A continuacion se muestra la informacion certificado con PIN 2602202649129843602 " +
                "PIN 2602202649129843602 " +
                "Oficina de Registro BARRANQUILLA " +
                "Matricula 368534 " +
                "Mensaje Consulta / generacion realizada correctamente para PIN 2602202649129843602 " +
                "Fecha Transaccion 20/02/2026 05:47 PM " +
                "</update></partial-response>";

        var result = adapter.parseResponse(response, "2602202649129843602");
        assertEquals(SnrValidationAdapter.ValidationOutcome.VALID, result);
    }

    @Test
    void shouldParseInvalidResponse() {
        String response = "<partial-response>" +
                "<update id=\"javax.faces.ViewRoot\">" +
                "Certificado no Encontrado " +
                "Lo sentimos pero el certificado con PIN 2602202649129843601 no ha sido encontrado en el sistema" +
                "</update></partial-response>";

        var result = adapter.parseResponse(response, "2602202649129843601");
        assertEquals(SnrValidationAdapter.ValidationOutcome.INVALID, result);
    }

    @Test
    void shouldReturnErrorForBlankResponse() {
        var result = adapter.parseResponse("", "12345");
        assertEquals(SnrValidationAdapter.ValidationOutcome.ERROR, result);
    }

    @Test
    void shouldReturnErrorForNullResponse() {
        var result = adapter.parseResponse(null, "12345");
        assertEquals(SnrValidationAdapter.ValidationOutcome.ERROR, result);
    }

    @Test
    void shouldParseValidResponseWithAccents() {
        String response = "A continuacion se muestra la informacion certificado con PIN 1234567890123456789 " +
                "Consulta / generacion realizada correctamente para PIN 1234567890123456789";

        var result = adapter.parseResponse(response, "1234567890123456789");
        assertEquals(SnrValidationAdapter.ValidationOutcome.VALID, result);
    }

    @Test
    void shouldParseInvalidResponseWithDifferentWording() {
        String response = "Lo sentimos pero el certificado con PIN 9999999999 no ha sido encontrado en el sistema";

        var result = adapter.parseResponse(response, "9999999999");
        assertEquals(SnrValidationAdapter.ValidationOutcome.INVALID, result);
    }

    @Test
    void shouldDetectValidByDataMatch() {
        String response = "PIN 2602202649129843602 Oficina de Registro BARRANQUILLA Matricula 368534";

        var result = adapter.parseResponse(response, "2602202649129843602");
        assertEquals(SnrValidationAdapter.ValidationOutcome.VALID, result);
    }
}
