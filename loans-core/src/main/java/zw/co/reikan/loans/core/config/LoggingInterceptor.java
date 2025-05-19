package zw.co.reikan.loans.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class LoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        logRequest(request, body);
        ClientHttpResponse response = execution.execute(request, body);
        logResponse(response);
        return response;
    }

    private void logRequest(HttpRequest request, byte[] body) {
        if (!logger.isInfoEnabled()) return;

        logger.info("REQUEST: {} {} | Headers: {} | Body: {}",
                request.getMethod(),
                request.getURI(),
                request.getHeaders(),
                new String(body, StandardCharsets.UTF_8));
    }

    private void logResponse(ClientHttpResponse response) {
        if (!logger.isInfoEnabled()) return;

        try {
            String bodyStr = new String(StreamUtils.copyToByteArray(response.getBody()), StandardCharsets.UTF_8);

            logger.info("RESPONSE: {} {} | Headers: {} | Body: {}",
                    response.getStatusCode(),
                    response.getStatusText(),
                    response.getHeaders(),
                    bodyStr);

            if (response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()) {
                logger.error("ERROR RESPONSE: {} - {}", response.getStatusCode(), bodyStr);
            }
        } catch (IOException e) {
            logger.warn("Failed to log response: {}", e.getMessage());
        }
    }
}