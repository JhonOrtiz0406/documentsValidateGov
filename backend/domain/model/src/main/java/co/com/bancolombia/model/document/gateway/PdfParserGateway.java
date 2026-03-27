package co.com.bancolombia.model.document.gateway;

import reactor.core.publisher.Mono;

public interface PdfParserGateway {
    Mono<String> extractPin(byte[] pdfBytes);
}
