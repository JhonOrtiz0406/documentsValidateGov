package co.com.bancolombia.model.document.gateway;

import reactor.core.publisher.Mono;

public interface DocumentValidationGateway {
    Mono<Boolean> validatePin(String pin);
}
