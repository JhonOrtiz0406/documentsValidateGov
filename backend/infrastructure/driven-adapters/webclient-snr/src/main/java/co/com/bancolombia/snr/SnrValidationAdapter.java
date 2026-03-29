package co.com.bancolombia.snr;

import co.com.bancolombia.model.document.gateway.DocumentValidationGateway;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SnrValidationAdapter implements DocumentValidationGateway {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36";

    private final WebClient webClient;
    private final String validationUrl;
    private final Duration requestTimeout;

    public SnrValidationAdapter(
            @Qualifier("snrWebClient") WebClient webClient,
            @Value("${snr.api.url}") String validationUrl,
            @Value("${snr.api.request-timeout-seconds:20}") long timeoutSeconds) {
        this.webClient = webClient;
        this.validationUrl = validationUrl;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public Mono<Boolean> validatePin(String pin) {
        log.info("SNR validatePin - PIN: {}", pin);

        // ── Step 1: GET the validation page, capture session cookies ──────────────
        return webClient.get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-CO,es;q=0.9,en;q=0.8")
                .exchangeToMono(response -> {
                    // Capture ALL Set-Cookie headers — JSF ViewState is session-bound,
                    // we MUST send the same JSESSIONID in the POST or the server
                    // will not find the session and return "no encontrado".
                    Map<String, String> sessionCookies = response.cookies()
                            .entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue().get(0).getValue(),
                                    (a, b) -> b));

                    log.info("SNR GET - status: {}, cookies captured: {}",
                            response.statusCode(), sessionCookies.keySet());

                    return response.bodyToMono(String.class)
                            .map(html -> Tuples.of(html, sessionCookies));
                })

                // ── Step 2: Parse form fields and POST with the session cookies ──
                .flatMap(tuple -> {
                    String initialHtml = tuple.getT1();
                    Map<String, String> sessionCookies = tuple.getT2();

                    Document doc = Jsoup.parse(initialHtml);
                    String viewState   = extractViewState(doc);
                    String pinInputId  = extractPinInputId(doc);
                    String buttonId    = extractSubmitButtonId(doc);

                    if (viewState == null) {
                        log.warn("SNR - ViewState not found in initial page. HTML snippet: {}",
                                initialHtml.length() > 400
                                        ? initialHtml.substring(0, 400) : initialHtml);
                        return Mono.error(new RuntimeException(
                                "No se pudo obtener el token de sesión de la SNR"));
                    }

                    log.info("SNR - ViewState found (len={}), input={}, button={}",
                            viewState.length(), pinInputId, buttonId);

                    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
                    formData.add("formValidation",              "formValidation");
                    formData.add(pinInputId,                    pin);
                    formData.add(buttonId,                      "");
                    formData.add("javax.faces.ViewState",       viewState);
                    formData.add("javax.faces.partial.ajax",    "true");
                    formData.add("javax.faces.source",          buttonId);
                    formData.add("javax.faces.partial.execute", "@all");
                    formData.add("javax.faces.partial.render",  "@all");

                    // Build POST request — re-attach every session cookie from the GET
                    WebClient.RequestBodySpec post = webClient.post()
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header("User-Agent",        USER_AGENT)
                            .header("Faces-Request",     "partial/ajax")
                            .header("X-Requested-With",  "XMLHttpRequest")
                            .header("Referer",           validationUrl)
                            .header("Accept",            "application/xml, text/xml, */*; q=0.01")
                            .header("Accept-Language",   "es-CO,es;q=0.9,en;q=0.8");

                    // Attach session cookies one by one
                    for (Map.Entry<String, String> cookie : sessionCookies.entrySet()) {
                        post = post.cookie(cookie.getKey(), cookie.getValue());
                    }

                    return post.body(BodyInserters.fromFormData(formData))
                            .retrieve()
                            .bodyToMono(String.class);
                })

                // ── Step 3: Parse the JSF partial-response ────────────────────────
                .map(responseXml -> {
                    ValidationOutcome outcome = parseResponse(responseXml, pin);
                    log.info("SNR result for PIN {}: {}", pin, outcome);
                    return outcome == ValidationOutcome.VALID;
                })
                .timeout(requestTimeout)
                .onErrorResume(ex -> {
                    log.error("SNR error for PIN {}: {}", pin, ex.getMessage());
                    return Mono.error(new RuntimeException(
                            "Error al validar con SNR: " + ex.getMessage()));
                });
    }

    // ── HTML form-field extractors ────────────────────────────────────────────────

    private String extractViewState(Document doc) {
        Element el = doc.selectFirst("input[name=javax.faces.ViewState]");
        if (el != null) {
            String val = el.attr("value");
            log.debug("ViewState extracted: {}...", val.length() > 20 ? val.substring(0, 20) : val);
            return val;
        }
        return null;
    }

    private String extractPinInputId(Document doc) {
        for (String sel : List.of(
                "form#formValidation input[type=text]",
                "form input[type=text]",
                "input[type=text]")) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.attr("id").isBlank()) {
                log.info("PIN input id='{}' (via selector '{}')", el.attr("id"), sel);
                return el.attr("id");
            }
        }
        log.warn("PIN input not found; using fallback id");
        return "formValidation:j_idt41";
    }

    private String extractSubmitButtonId(Document doc) {
        for (String sel : List.of(
                "form#formValidation button[type=submit]",
                "form#formValidation input[type=submit]",
                "form button[type=submit]",
                "form input[type=submit]",
                "button[type=submit]",
                "input[type=submit]")) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.attr("id").isBlank()) {
                log.info("Submit button id='{}' (via selector '{}')", el.attr("id"), sel);
                return el.attr("id");
            }
        }
        log.warn("Submit button not found; using fallback id");
        return "formValidation:j_idt42";
    }

    // ── Response parser ───────────────────────────────────────────────────────────

    ValidationOutcome parseResponse(String responseContent, String pin) {
        if (responseContent == null || responseContent.isBlank()) {
            log.warn("SNR returned empty response for PIN {}", pin);
            return ValidationOutcome.ERROR;
        }

        // Log a generous snippet so we can see what the server actually returned
        log.info("SNR raw response for PIN {} (first 1500 chars): {}",
                pin, responseContent.length() > 1500
                        ? responseContent.substring(0, 1500) + "..." : responseContent);

        String normalized = normalize(responseContent);

        // ── INVALID signals ───────────────────────────────────────────────────────
        if (normalized.contains("certificado no encontrado")
                || normalized.contains("no ha sido encontrado en el sistema")
                || normalized.contains("no se encontro")
                || normalized.contains("pin no existe")
                || normalized.contains("no existe el certificado")) {
            log.info("SNR: PIN {} → NOT FOUND", pin);
            return ValidationOutcome.INVALID;
        }

        // ── VALID signals ─────────────────────────────────────────────────────────
        if (normalized.contains("consulta / generacion realizada correctamente")
                || normalized.contains("consulta/generacion realizada correctamente")
                || normalized.contains("generacion realizada correctamente")
                || normalized.contains("a continuacion se muestra la informacion certificado con pin")
                || normalized.contains("informacion certificado con pin")) {
            log.info("SNR: PIN {} → VALID (success phrase)", pin);
            return ValidationOutcome.VALID;
        }

        // Certificate data present together with the PIN → VALID
        if (normalized.contains("oficina de registro") && normalized.contains("matricula")
                && normalized.contains(pin.toLowerCase(Locale.ROOT))) {
            log.info("SNR: PIN {} → VALID (registration data match)", pin);
            return ValidationOutcome.VALID;
        }

        // Looser: any response carrying the PIN alongside office/registry info
        if ((normalized.contains("oficina") || normalized.contains("registro"))
                && normalized.contains(pin.toLowerCase(Locale.ROOT))) {
            log.info("SNR: PIN {} → VALID (loose data match)", pin);
            return ValidationOutcome.VALID;
        }

        log.warn("SNR: PIN {} → outcome UNDETERMINED. Normalized (first 800): {}",
                pin, normalized.length() > 800 ? normalized.substring(0, 800) + "..." : normalized);
        return ValidationOutcome.ERROR;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private String normalize(String input) {
        if (input == null) return "";
        String nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return WHITESPACE.matcher(nfd.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    enum ValidationOutcome { VALID, INVALID, ERROR }
}
