package co.com.bancolombia.snr;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnrValidationAdapterTest {

    private final SnrValidationAdapter adapter = new SnrValidationAdapter(WebClient.builder().baseUrl("http://localhost").build(), 5);

    @Test
    void shouldParseSuccessfulHtmlAsValid() {
        String html = """
                <html>
                  <head><title>Validacion de certificados</title></head>
                  <body>
                    <h1>Informacion 2603097148130907463</h1>
                    <p>A continuación se muestra la informacion certificado con PIN 2603097148130907463</p>
                    <table>
                      <tr><td>PIN</td><td>2603097148130907463</td></tr>
                      <tr><td>Mensaje</td><td>Consulta / generacion realizada correctamente para PIN 2603097148130907463</td></tr>
                    </table>
                  </body>
                </html>
                """;

        assertEquals(SnrValidationAdapter.ValidationOutcome.VALID, adapter.parseHtmlOutcome(html));
    }

    @Test
    void shouldParseNotFoundHtmlAsInvalid() {
        String html = """
                <html>
                  <head><title>Validacion de certificados</title></head>
                  <body>
                    <h1>Certificado no Encontrado</h1>
                    <p>Lo sentimos pero el certificado con PIN 2603097148130907461 no ha sido encontrado en el sistema</p>
                  </body>
                </html>
                """;

        assertEquals(SnrValidationAdapter.ValidationOutcome.INVALID, adapter.parseHtmlOutcome(html));
    }

    @Test
    void shouldReturnErrorForAmbiguousHtml() {
        String html = """
                <html>
                  <head><title>Validacion de certificados</title></head>
                  <body>
                    <p>Servicio temporalmente no disponible</p>
                  </body>
                </html>
                """;

        assertEquals(SnrValidationAdapter.ValidationOutcome.ERROR, adapter.parseHtmlOutcome(html));
    }
}
