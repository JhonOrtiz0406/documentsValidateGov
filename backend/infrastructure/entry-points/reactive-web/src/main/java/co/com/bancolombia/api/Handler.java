package co.com.bancolombia.api;

import co.com.bancolombia.model.document.DocumentValidationResult;
import co.com.bancolombia.usecase.validate.ValidateDocumentsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class Handler {

    private final ValidateDocumentsUseCase validateDocumentsUseCase;

    public Mono<ServerResponse> validateDocuments(ServerRequest serverRequest) {
        log.info("Received document validation request");

        Flux<ValidateDocumentsUseCase.FileData> files = serverRequest.multipartData()
                .flatMapMany(multipart -> {
                    var parts = multipart.get("files");
                    if (parts == null || parts.isEmpty()) {
                        return Flux.error(new IllegalArgumentException("No files uploaded"));
                    }
                    return Flux.fromIterable(parts);
                })
                .filter(part -> part instanceof FilePart)
                .cast(FilePart.class)
                .flatMap(this::toFileData);

        Flux<DocumentValidationResult> results = validateDocumentsUseCase.validate(files);

        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(results, DocumentValidationResult.class);
    }

    private Mono<ValidateDocumentsUseCase.FileData> toFileData(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return new ValidateDocumentsUseCase.FileData(filePart.filename(), bytes);
                });
    }

    public Mono<ServerResponse> healthCheck(ServerRequest serverRequest) {
        return ServerResponse.ok().bodyValue("{\"status\":\"UP\"}");
    }
}
