package co.com.bancolombia.usecase.validate;

import co.com.bancolombia.model.document.DocumentValidationResult;
import co.com.bancolombia.model.document.ValidationStatus;
import co.com.bancolombia.model.document.gateway.DocumentValidationGateway;
import co.com.bancolombia.model.document.gateway.PdfParserGateway;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.logging.Level;
import java.util.logging.Logger;

@RequiredArgsConstructor
public class ValidateDocumentsUseCase {

    private static final Logger log = Logger.getLogger(ValidateDocumentsUseCase.class.getName());

    private final PdfParserGateway pdfParserGateway;
    private final DocumentValidationGateway documentValidationGateway;

    public Flux<DocumentValidationResult> validate(Flux<FileData> files) {
        return files.flatMap(this::processFile, 4);
    }

    private Mono<DocumentValidationResult> processFile(FileData fileData) {
        log.info("Processing file: " + fileData.fileName());
        return pdfParserGateway.extractPin(fileData.content())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(pin -> validatePin(fileData.fileName(), pin))
                .switchIfEmpty(Mono.just(DocumentValidationResult.builder()
                        .fileName(fileData.fileName())
                        .pin("N/A")
                        .status(ValidationStatus.ERROR)
                        .message("No se pudo extraer el PIN del documento")
                        .build()))
                .onErrorResume(e -> {
                    log.log(Level.SEVERE, "Error processing file " + fileData.fileName() + ": " + e.getMessage(), e);
                    return Mono.just(DocumentValidationResult.builder()
                            .fileName(fileData.fileName())
                            .pin("N/A")
                            .status(ValidationStatus.ERROR)
                            .message("Error procesando archivo: " + e.getMessage())
                            .build());
                });
    }

    private Mono<DocumentValidationResult> validatePin(String fileName, String pin) {
        if (pin == null || pin.isBlank()) {
            return Mono.just(DocumentValidationResult.builder()
                    .fileName(fileName)
                    .pin("N/A")
                    .status(ValidationStatus.ERROR)
                    .message("No se pudo extraer el PIN del documento")
                    .build());
        }

        log.info("Validating PIN " + pin + " for file " + fileName);
        return documentValidationGateway.validatePin(pin)
                .map(isValid -> DocumentValidationResult.builder()
                        .fileName(fileName)
                        .pin(pin)
                        .status(isValid ? ValidationStatus.VALID : ValidationStatus.INVALID)
                        .message(isValid
                                ? "Documento válido - certificado autenticado"
                                : "Documento inválido - certificado no encontrado")
                        .build())
                .onErrorResume(e -> {
                    log.log(Level.SEVERE, "Error validating PIN " + pin + " for file " + fileName, e);
                    return Mono.just(DocumentValidationResult.builder()
                            .fileName(fileName)
                            .pin(pin)
                            .status(ValidationStatus.ERROR)
                            .message("Error al validar con SNR: " + e.getMessage())
                            .build());
                });
    }

    public record FileData(String fileName, byte[] content) {}
}
