package co.com.bancolombia.model.document.gateway;

import co.com.bancolombia.model.document.PinExtractionResult;
import reactor.core.publisher.Mono;

public interface PdfParserGateway {
    Mono<PinExtractionResult> extractPins(byte[] pdfBytes);
}
