package com.innowise.apigateway.integration.userservice;

import com.innowise.apigateway.config.UserServiceProperties;
import com.innowise.apigateway.exception.EmailAlreadyExistsException;
import com.innowise.apigateway.exception.UserServiceIntegrationException;
import com.innowise.apigateway.integration.userservice.dto.UserProfileRequest;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

@Service
public class UserServiceClient {

    private static final Logger log = LoggerFactory.getLogger(UserServiceClient.class);

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final WebClient webClient;
    private final UserServiceProperties properties;

    public UserServiceClient(UserServiceProperties properties) {
        this.properties = properties;
        // Explicit timeouts: one hung User Service call must not block a registration indefinitely.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(10));
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public Mono<JsonNode> createProfile(UserProfileRequest request) {
        return webClient.post()
                .uri("/api/internal/users")
                .header(INTERNAL_API_KEY_HEADER, properties.internalApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorMap(this::translate);
    }


    private RuntimeException translate(Throwable ex) {
        if (ex instanceof UserServiceIntegrationException || ex instanceof EmailAlreadyExistsException) {
            return (RuntimeException) ex;
        }
        if (ex instanceof WebClientResponseException responseEx) {
            log.warn("User Service call failed with status {}: {}", responseEx.getStatusCode(),
                    responseEx.getResponseBodyAsString());
            if (responseEx.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                return new EmailAlreadyExistsException("Email is already registered in User Service");
            }
            return new UserServiceIntegrationException("User Service failed to save the profile ("
                    + responseEx.getStatusCode().value() + ")", ex);
        }
        if (ex instanceof WebClientRequestException) {
            return new UserServiceIntegrationException("User Service is unavailable", ex);
        }
        return new UserServiceIntegrationException("User Service call failed", ex);
    }
}
