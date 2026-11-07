package withoutjjin.withoutjjin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class DocumentConversionConfig {

    @Bean
    public RestClient wordPdfRestClient(
            @Value("${document.conversion.base-url}") URI baseUrl,
            @Value("${document.conversion.connect-timeout}") Duration connectTimeout,
            @Value("${document.conversion.read-timeout}") Duration readTimeout
    ) {
        if (baseUrl.getHost() == null
                || !("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("변환 서버 주소는 HTTP 또는 HTTPS 주소여야 합니다.");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("변환 서버의 연결·응답 대기시간은 0보다 커야 합니다.");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // DOCX가 리다이렉트된 다른 서버로 전송되지 않도록 한다.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
