package co.com.bancolombia.config;

import co.com.bancolombia.model.document.gateway.DocumentValidationGateway;
import co.com.bancolombia.model.document.gateway.PdfParserGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UseCasesConfigTest {

    @Test
    void testUseCaseBeansExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            String[] beanNames = context.getBeanDefinitionNames();

            boolean useCaseBeanFound = false;
            for (String beanName : beanNames) {
                if (beanName.contains("UseCase") || beanName.contains("useCase")) {
                    useCaseBeanFound = true;
                    break;
                }
            }

            assertTrue(useCaseBeanFound, "No beans containing 'UseCase' were found");
        }
    }

    @Configuration
    @Import(UseCasesConfig.class)
    static class TestConfig {

        @Bean
        public PdfParserGateway pdfParserGateway() {
            return pdfBytes -> Mono.just("MOCK_PIN");
        }

        @Bean
        public DocumentValidationGateway documentValidationGateway() {
            return pin -> Mono.just(true);
        }
    }
}