package co.com.bancolombia.snr;

import co.com.bancolombia.model.document.gateway.DocumentValidationGateway;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SnrValidationAdapter implements DocumentValidationGateway {

    private static final int HTML_SNIPPET_LENGTH = 300;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String SUCCESS_MESSAGE = "consulta / generacion realizada correctamente";
    private static final String SUCCESS_INFO_PREFIX = "a continuacion se muestra la informacion certificado con pin";
    private static final String NOT_FOUND_MESSAGE = "certificado no encontrado";
    private static final String NOT_FOUND_DETAIL = "no ha sido encontrado en el sistema";

    private final WebClient webClient;
    private final Duration requestTimeout;

    public SnrValidationAdapter(
            @Qualifier("snrWebClient") WebClient webClient,
            @Value("${snr.api.request-timeout-seconds:5}") long timeoutSeconds) {
        this.webClient = webClient;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public Mono<Boolean> validatePin(String pin) {
        log.info("SNR validatePin - sending PIN: {}", pin);

        return webClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("pin", pin))
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(html -> {
                                log.info("SNR validatePin - HTTP status: {}", status.value());
                                log.info("SNR validatePin - HTML length: {}", html.length());
                                log.info("SNR validatePin - HTML snippet: {}", snippet(html));

                                ValidationOutcome outcome = parseHtmlOutcome(html);
                                log.info("SNR validatePin - parsed outcome for PIN {}: {}", pin, outcome);
                                return outcome == ValidationOutcome.VALID;
                            });
                })
                .timeout(requestTimeout)
                .onErrorResume(ex -> {
                    log.error("SNR validatePin - error for PIN {}: {}", pin, ex.getMessage(), ex);
                    return Mono.just(false);
                });
    }

    ValidationOutcome parseHtmlOutcome(String html) {
        if (html == null || html.isBlank()) {
            return ValidationOutcome.ERROR;
        }

        Document doc = Jsoup.parse(html);
        String title = normalize(doc.title());
        String bodyText = normalize(doc.body().text());
        String allText = (title + " " + bodyText).trim();

        log.info("SNR parse - title: {}", title);
        log.info("SNR parse - contains success message: {}", allText.contains(normalize(SUCCESS_MESSAGE)));
        log.info("SNR parse - contains info prefix: {}", allText.contains(normalize(SUCCESS_INFO_PREFIX)));
        log.info("SNR parse - contains not-found detail: {}", allText.contains(normalize(NOT_FOUND_DETAIL)));

        if (containsAny(allText,
                NOT_FOUND_MESSAGE,
                "certificado no fue encontrado",
                "lo sentimos pero el certificado con pin",
                NOT_FOUND_DETAIL,
                "no existe el certificado")) {
            return ValidationOutcome.INVALID;
        }

        if (containsAny(allText,
                SUCCESS_MESSAGE,
                "mensaje consulta / generacion realizada correctamente",
                SUCCESS_INFO_PREFIX,
                "informacion certificado con pin")) {
            return ValidationOutcome.VALID;
        }

        if (allText.contains("informacion")
                && (allText.contains("certificado") || allText.contains("pin"))
                && allText.contains("consulta")
                && allText.contains("generacion")) {
            return ValidationOutcome.VALID;
        }

        return ValidationOutcome.ERROR;
    }

    private boolean containsAny(String source, String... candidates) {
        for (String candidate : candidates) {
            if (source.contains(normalize(candidate))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String input) {
        if (input == null) {
            return "";
        }
        String nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String lower = nfd.toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(lower).replaceAll(" ").trim();
    }

    private String snippet(String html) {
        if (html == null) {
            return "";
        }
        String sanitized = WHITESPACE.matcher(html).replaceAll(" ").trim();
        return sanitized.length() <= HTML_SNIPPET_LENGTH
                ? sanitized
                : sanitized.substring(0, HTML_SNIPPET_LENGTH) + "...";
    }

    enum ValidationOutcome {
        VALID, INVALID, ERROR
    }
}
