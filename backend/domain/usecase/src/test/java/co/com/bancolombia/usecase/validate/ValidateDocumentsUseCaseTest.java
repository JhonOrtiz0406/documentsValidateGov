package co.com.bancolombia.usecase.validate;

import co.com.bancolombia.model.document.DocumentValidationResult;
import co.com.bancolombia.model.document.PinExtractionResult;
import co.com.bancolombia.model.document.ValidationStatus;
import co.com.bancolombia.model.document.gateway.DocumentValidationGateway;
import co.com.bancolombia.model.document.gateway.PdfParserGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidateDocumentsUseCaseTest {

    @Mock
    private PdfParserGateway pdfParserGateway;

    @Mock
    private DocumentValidationGateway documentValidationGateway;

    private ValidateDocumentsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ValidateDocumentsUseCase(pdfParserGateway, documentValidationGateway);
    }

    @Test
    void shouldReturnValidWhenPinIsValid() {
        when(pdfParserGateway.extractPins(any(byte[].class)))
                .thenReturn(Mono.just(PinExtractionResult.single("1234567890123456789")));
        when(documentValidationGateway.validatePin("1234567890123456789"))
                .thenReturn(Mono.just(true));

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result ->
                        result.getStatus() == ValidationStatus.VALID
                                && "1234567890123456789".equals(result.getPin())
                                && "test.pdf".equals(result.getFileName()))
                .verifyComplete();
    }

    @Test
    void shouldReturnInvalidWhenPinIsInvalid() {
        when(pdfParserGateway.extractPins(any(byte[].class)))
                .thenReturn(Mono.just(PinExtractionResult.single("1234567890123456789")));
        when(documentValidationGateway.validatePin("1234567890123456789"))
                .thenReturn(Mono.just(false));

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result -> result.getStatus() == ValidationStatus.INVALID)
                .verifyComplete();
    }

    @Test
    void shouldReturnErrorWhenPinExtractionFails() {
        when(pdfParserGateway.extractPins(any(byte[].class)))
                .thenReturn(Mono.error(new RuntimeException("Parse error")));

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result ->
                        result.getStatus() == ValidationStatus.ERROR
                                && result.getMessage().contains("Parse error"))
                .verifyComplete();
    }

    @Test
    void shouldReturnErrorWhenNoPinFound() {
        when(pdfParserGateway.extractPins(any(byte[].class)))
                .thenReturn(Mono.empty());

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result ->
                        result.getStatus() == ValidationStatus.ERROR
                                && result.getMessage().contains("No se pudo extraer"))
                .verifyComplete();
    }

    @Test
    void shouldReturnAlertWhenMultipleDifferentPinsFoundInDocument() {
        PinExtractionResult conflict = PinExtractionResult.conflict(
                List.of("1111111111111111111", "2222222222222222222"));

        when(pdfParserGateway.extractPins(any(byte[].class)))
                .thenReturn(Mono.just(conflict));

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result ->
                        result.getStatus() == ValidationStatus.ALERT
                                && result.getMessage().contains("múltiples PINs")
                                && result.getMessage().contains("1111111111111111111")
                                && result.getMessage().contains("2222222222222222222"))
                .verifyComplete();
    }

    @Test
    void shouldProcessMultipleFilesInParallel() {
        when(pdfParserGateway.extractPins(any(byte[].class)))
                .thenReturn(Mono.just(PinExtractionResult.single("1234567890123456789")));
        when(documentValidationGateway.validatePin(anyString()))
                .thenReturn(Mono.just(true));

        var files = Flux.just(
                new ValidateDocumentsUseCase.FileData("file1.pdf", new byte[]{1}),
                new ValidateDocumentsUseCase.FileData("file2.pdf", new byte[]{2}),
                new ValidateDocumentsUseCase.FileData("file3.pdf", new byte[]{3})
        );

        StepVerifier.create(useCase.validate(files))
                .expectNextCount(3)
                .verifyComplete();
    }
}
