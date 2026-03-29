package co.com.bancolombia.snr.config;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
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
            @Value("${snr.api.timeout:20000}") int timeoutMs) {

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(timeoutMs))
                .followRedirect(true)
                .secure(spec -> {
                    try {
                        // Government sites sometimes have certificate-chain issues;
                        // trust all to avoid SSL handshake failures on the SNR endpoint.
                        spec.sslContext(
                                SslContextBuilder.forClient()
                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                        .build());
                    } catch (Exception ignored) {
                        // fall back to default SSL context
                    }
                });

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}
