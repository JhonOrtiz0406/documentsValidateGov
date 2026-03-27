package co.com.bancolombia.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DocumentValidationResult {
    private String fileName;
    private String pin;
    private ValidationStatus status;
    private String message;
}
