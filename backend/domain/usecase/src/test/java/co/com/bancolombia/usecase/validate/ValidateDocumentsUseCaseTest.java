package co.com.bancolombia.usecase.validate;

import co.com.bancolombia.model.document.DocumentValidationResult;
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
        when(pdfParserGateway.extractPin(any(byte[].class)))
                .thenReturn(Mono.just("ABC123"));
        when(documentValidationGateway.validatePin("ABC123"))
                .thenReturn(Mono.just(true));

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result ->
                        result.getStatus() == ValidationStatus.VALID
                                && "ABC123".equals(result.getPin())
                                && "test.pdf".equals(result.getFileName()))
                .verifyComplete();
    }

    @Test
    void shouldReturnInvalidWhenPinIsInvalid() {
        when(pdfParserGateway.extractPin(any(byte[].class)))
                .thenReturn(Mono.just("INVALID_PIN"));
        when(documentValidationGateway.validatePin("INVALID_PIN"))
                .thenReturn(Mono.just(false));

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result ->
                        result.getStatus() == ValidationStatus.INVALID)
                .verifyComplete();
    }

    @Test
    void shouldReturnErrorWhenPinExtractionFails() {
        when(pdfParserGateway.extractPin(any(byte[].class)))
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
        when(pdfParserGateway.extractPin(any(byte[].class)))
                .thenReturn(Mono.just(""));

        var files = Flux.just(new ValidateDocumentsUseCase.FileData("test.pdf", new byte[]{1, 2, 3}));

        StepVerifier.create(useCase.validate(files))
                .expectNextMatches(result ->
                        result.getStatus() == ValidationStatus.ERROR
                                && result.getMessage().contains("No se pudo extraer"))
                .verifyComplete();
    }

    @Test
    void shouldProcessMultipleFilesInParallel() {
        when(pdfParserGateway.extractPin(any(byte[].class)))
                .thenReturn(Mono.just("PIN001"));
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
