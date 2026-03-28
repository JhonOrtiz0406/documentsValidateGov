package co.com.bancolombia.snr;

import co.com.bancolombia.model.document.gateway.DocumentValidationGateway;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SnrValidationAdapter implements DocumentValidationGateway {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final WebClient webClient;
    private final String validationUrl;
    private final Duration requestTimeout;

    public SnrValidationAdapter(
            @Qualifier("snrWebClient") WebClient webClient,
            @Value("${snr.api.url}") String validationUrl,
            @Value("${snr.api.request-timeout-seconds:15}") long timeoutSeconds) {
        this.webClient = webClient;
        this.validationUrl = validationUrl;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public Mono<Boolean> validatePin(String pin) {
        log.info("SNR validatePin - starting validation for PIN: {}", pin);

        // Step 1: GET to obtain the JSF ViewState token
        return webClient.get()
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(initialHtml -> {
                    Document doc = Jsoup.parse(initialHtml);
                    String viewState = extractViewState(doc);
                    String formInputId = extractPinInputId(doc);
                    String formButtonId = extractSubmitButtonId(doc);

                    if (viewState == null) {
                        log.warn("SNR validatePin - could not extract ViewState from initial page");
                        return Mono.error(new RuntimeException("No se pudo obtener el token de sesión de la SNR"));
                    }

                    log.info("SNR validatePin - obtained ViewState, input={}, button={}", formInputId, formButtonId);

                    // Step 2: POST with the PIN and ViewState
                    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
                    formData.add("formValidation", "formValidation");
                    formData.add(formInputId, pin);
                    formData.add(formButtonId, "");
                    formData.add("javax.faces.ViewState", viewState);
                    formData.add("javax.faces.partial.ajax", "true");
                    formData.add("javax.faces.source", formButtonId);
                    formData.add("javax.faces.partial.execute", "@all");
                    formData.add("javax.faces.partial.render", "@all");

                    return webClient.post()
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header("Faces-Request", "partial/ajax")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .body(BodyInserters.fromFormData(formData))
                            .retrieve()
                            .bodyToMono(String.class);
                })
                .map(responseXml -> {
                    ValidationOutcome outcome = parseResponse(responseXml, pin);
                    log.info("SNR validatePin - result for PIN {}: {}", pin, outcome);
                    return outcome == ValidationOutcome.VALID;
                })
                .timeout(requestTimeout)
                .onErrorResume(ex -> {
                    log.error("SNR validatePin - error for PIN {}: {}", pin, ex.getMessage());
                    return Mono.error(new RuntimeException("Error al validar con SNR: " + ex.getMessage()));
                });
    }

    private String extractViewState(Document doc) {
        Element viewStateInput = doc.selectFirst("input[name=javax.faces.ViewState]");
        return viewStateInput != null ? viewStateInput.attr("value") : null;
    }

    private String extractPinInputId(Document doc) {
        // Try the formValidation form first
        for (String sel : List.of(
                "form#formValidation input[type=text]",
                "form input[type=text]",
                "input[type=text]")) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.attr("id").isBlank()) {
                log.info("SNR - PIN input id found via '{}': {}", sel, el.attr("id"));
                return el.attr("id");
            }
        }
        log.warn("SNR - PIN input not found via selectors, using fallback id");
        return "formValidation:j_idt41";
    }

    private String extractSubmitButtonId(Document doc) {
        // JSF may render either <button type="submit"> or <input type="submit">
        for (String sel : List.of(
                "form#formValidation button[type=submit]",
                "form#formValidation input[type=submit]",
                "form button[type=submit]",
                "form input[type=submit]",
                "button[type=submit]",
                "input[type=submit]")) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.attr("id").isBlank()) {
                log.info("SNR - submit button id found via '{}': {}", sel, el.attr("id"));
                return el.attr("id");
            }
        }
        log.warn("SNR - submit button not found via selectors, using fallback id");
        return "formValidation:j_idt42";
    }

    ValidationOutcome parseResponse(String responseContent, String pin) {
        if (responseContent == null || responseContent.isBlank()) {
            return ValidationOutcome.ERROR;
        }

        // Always log the response snippet to diagnose unexpected outcomes
        log.info("SNR parseResponse - PIN {} - response snippet: {}", pin, snippet(responseContent));

        String normalized = normalize(responseContent);

        // Check for "certificado no encontrado" - INVALID
        if (normalized.contains("certificado no encontrado")
                || normalized.contains("no ha sido encontrado en el sistema")
                || normalized.contains("no se encontro")
                || normalized.contains("pin no existe")) {
            log.info("SNR parseResponse - PIN {} NOT FOUND", pin);
            return ValidationOutcome.INVALID;
        }

        // Check for success indicators - VALID
        if (normalized.contains("consulta / generacion realizada correctamente")
                || normalized.contains("consulta/generacion realizada correctamente")
                || normalized.contains("a continuacion se muestra la informacion certificado con pin")
                || normalized.contains("generacion realizada correctamente")) {
            log.info("SNR parseResponse - PIN {} VALID (success text)", pin);
            return ValidationOutcome.VALID;
        }

        // Additional check: response contains the PIN with associated registration data
        if (normalized.contains("oficina de registro") && normalized.contains("matricula")
                && normalized.contains(pin.toLowerCase(Locale.ROOT))) {
            log.info("SNR parseResponse - PIN {} VALID (data match)", pin);
            return ValidationOutcome.VALID;
        }

        // Looser match: any response that contains the PIN and registration office info
        if ((normalized.contains("oficina") || normalized.contains("registro"))
                && normalized.contains(pin.toLowerCase(Locale.ROOT))) {
            log.info("SNR parseResponse - PIN {} VALID (loose data match)", pin);
            return ValidationOutcome.VALID;
        }

        log.warn("SNR parseResponse - unable to determine outcome for PIN {}. Normalized snippet: {}",
                pin, normalized.length() > 600 ? normalized.substring(0, 600) + "..." : normalized);
        return ValidationOutcome.ERROR;
    }

    private String normalize(String input) {
        if (input == null) return "";
        String nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String lower = nfd.toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(lower).replaceAll(" ").trim();
    }

    private String snippet(String text) {
        if (text == null) return "";
        String clean = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500) + "...";
    }

    enum ValidationOutcome {
        VALID, INVALID, ERROR
    }
}
