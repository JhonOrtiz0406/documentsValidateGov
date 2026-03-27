package co.com.bancolombia.snr;

import co.com.bancolombia.model.document.gateway.DocumentValidationGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class SnrValidationAdapter implements DocumentValidationGateway {

    private final WebClient webClient;

    public SnrValidationAdapter(@Qualifier("snrWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<Boolean> validatePin(String pin) {
        log.info("Calling SNR validation API for PIN: {}", pin);

        return webClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("codigoVerificacion", pin))
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseResponse)
                .doOnSuccess(result -> log.info("SNR validation result for PIN {}: {}", pin, result))
                .doOnError(e -> log.error("SNR validation error for PIN {}: {}", pin, e.getMessage()));
    }

    private boolean parseResponse(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        String lower = html.toLowerCase();
        // Check for indicators of a valid certificate in the response
        return lower.contains("certificado") && !lower.contains("no se encontr")
                && !lower.contains("no existe") && !lower.contains("no válido")
                && !lower.contains("error");
    }
}
