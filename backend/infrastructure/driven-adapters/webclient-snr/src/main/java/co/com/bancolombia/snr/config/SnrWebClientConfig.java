package co.com.bancolombia.snr.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class SnrWebClientConfig {

    @Bean("snrWebClient")
    public WebClient snrWebClient(
            @Value("${snr.api.url}") String baseUrl,
            @Value("${snr.api.timeout:10000}") int timeout) {

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(timeout))
                .followRedirect(true);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }
}
