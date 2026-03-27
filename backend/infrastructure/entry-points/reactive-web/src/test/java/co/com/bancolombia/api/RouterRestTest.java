package co.com.bancolombia.api;

import co.com.bancolombia.model.document.DocumentValidationResult;
import co.com.bancolombia.model.document.ValidationStatus;
import co.com.bancolombia.usecase.validate.ValidateDocumentsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ContextConfiguration(classes = {RouterRest.class, Handler.class})
@WebFluxTest
class RouterRestTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ValidateDocumentsUseCase validateDocumentsUseCase;

    @Test
    void testValidateDocuments() {
        DocumentValidationResult mockResult = DocumentValidationResult.builder()
                .fileName("test.pdf")
                .pin("ABC123")
                .status(ValidationStatus.VALID)
                .message("Documento válido")
                .build();

        when(validateDocumentsUseCase.validate(any(Flux.class)))
                .thenReturn(Flux.just(mockResult));

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("files", new ByteArrayResource(new byte[]{1, 2, 3}) {
            @Override
            public String getFilename() {
                return "test.pdf";
            }
        }).contentType(MediaType.APPLICATION_PDF);

        webTestClient.post()
                .uri("/api/validate-documents")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(DocumentValidationResult.class)
                .hasSize(1);
    }

    @Test
    void testHealthCheck() {
        webTestClient.get()
                .uri("/api/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }
}
