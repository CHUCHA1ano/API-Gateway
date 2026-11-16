package com.innowise.apigateway.integration.keycloak;

import com.innowise.apigateway.config.KeycloakProperties;
import com.innowise.apigateway.exception.EmailAlreadyExistsException;
import com.innowise.apigateway.exception.KeycloakIntegrationException;
import com.innowise.apigateway.integration.keycloak.dto.CredentialRepresentation;
import com.innowise.apigateway.integration.keycloak.dto.KeycloakUserRequest;
import com.innowise.apigateway.integration.keycloak.dto.RoleRepresentation;
import com.innowise.apigateway.integration.keycloak.dto.TokenResponse;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    private static final String USER_ROLE = "USER";

    private static final Duration ADMIN_TOKEN_CACHE_TTL = Duration.ofMinutes(10);

    private final WebClient webClient;
    private final KeycloakProperties properties;
    private final Map<String, String> roleIds = new ConcurrentHashMap<>();
    private final AtomicReference<CachedAdminToken> cachedAdminToken = new AtomicReference<>();

    public KeycloakAdminClient(KeycloakProperties properties) {
        this.properties = properties;
        // Explicit timeouts: one hung Keycloak call must not block a registration indefinitely.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(10));
        this.webClient = WebClient.builder()
                .baseUrl(properties.url())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }


    public Mono<UUID> createUser(String email, String password, String firstName, String lastName) {
        KeycloakUserRequest request = new KeycloakUserRequest(
                email,
                email,
                firstName,
                lastName,
                true,
                true,
                List.of(CredentialRepresentation.password(password)));

        return adminToken()
                .flatMap(token -> webClient.post()
                        .uri("/admin/realms/{realm}/users", properties.realm())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .retrieve()
                        .toBodilessEntity()
                        .map(this::extractUserId)
                        .onErrorMap(ex -> translate(ex, "create user")))
                .flatMap(userId -> assignRealmRole(userId)
                        .onErrorResume(roleEx -> compensateCreatedUser(userId, roleEx))
                        .thenReturn(userId));
    }

    public Mono<Void> deleteUser(UUID userId) {
        return adminToken()
                .flatMap(token -> webClient.delete()
                        .uri("/admin/realms/{realm}/users/{id}", properties.realm(), userId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .toBodilessEntity())
                .onErrorResume(ex -> {
                    if (ex instanceof WebClientResponseException responseEx
                            && responseEx.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return Mono.empty();
                    }
                    return Mono.error(translate(ex, "delete user"));
                })
                .then();
    }

    private Mono<Void> compensateCreatedUser(UUID userId, Throwable originalError) {
        return deleteUser(userId)
                .onErrorResume(compensationEx -> {
                    log.error("Failed to compensate: Keycloak user {} remains without the assigned role",
                            userId, compensationEx);
                    return Mono.empty();
                })
                .then(Mono.error(originalError));
    }

    private Mono<Void> assignRealmRole(UUID userId) {
        return realmRoleId(USER_ROLE)
                .flatMap(roleId -> adminToken()
                        .flatMap(token -> webClient.post()
                                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm",
                                        properties.realm(), userId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(List.of(new RoleRepresentation(roleId, USER_ROLE)))
                                .retrieve()
                                .toBodilessEntity())
                        .onErrorMap(ex -> translate(ex, "assign role " + USER_ROLE)))
                .then();
    }

    private Mono<String> realmRoleId(String roleName) {
        String cached = roleIds.get(roleName);
        if (cached != null) {
            return Mono.just(cached);
        }
        return adminToken()
                .flatMap(token -> webClient.get()
                        .uri("/admin/realms/{realm}/roles/{name}", properties.realm(), roleName)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .bodyToMono(RoleRepresentation.class))
                .flatMap(role -> {
                    if (role == null || role.id() == null) {
                        return Mono.error(new KeycloakIntegrationException(
                                "Keycloak realm role not found: " + roleName));
                    }
                    roleIds.put(roleName, role.id());
                    return Mono.just(role.id());
                })
                .onErrorMap(ex -> translate(ex, "read role " + roleName));
    }

    private Mono<String> adminToken() {
        CachedAdminToken cached = cachedAdminToken.get();
        if (cached != null && Instant.now().isBefore(cached.validUntil())) {
            return cached.token();
        }
        Mono<String> fresh = requestAdminToken().cache();
        cachedAdminToken.set(new CachedAdminToken(fresh, Instant.now().plus(ADMIN_TOKEN_CACHE_TTL)));
        return fresh;
    }

    private Mono<String> requestAdminToken() {
        return Mono.defer(() -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());

            return webClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .flatMap(token -> {
                        if (token == null || token.accessToken() == null) {
                            return Mono.error(new KeycloakIntegrationException(
                                    "Keycloak did not return an admin access token"));
                        }
                        return Mono.just(token.accessToken());
                    });
        }).onErrorMap(ex -> translate(ex, "obtain admin token"));
    }

    private UUID extractUserId(org.springframework.http.ResponseEntity<Void> response) {
        java.net.URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakIntegrationException("Keycloak did not return a user location");
        }
        String path = location.getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new KeycloakIntegrationException("Keycloak returned an invalid user id: " + id, ex);
        }
    }

    private RuntimeException translate(Throwable ex, String action) {
        if (ex instanceof KeycloakIntegrationException || ex instanceof EmailAlreadyExistsException) {
            return (RuntimeException) ex;
        }
        if (ex instanceof WebClientResponseException responseEx) {
            log.warn("Keycloak call \"{}\" failed with status {}: {}", action, responseEx.getStatusCode(),
                    responseEx.getResponseBodyAsString());
            if (responseEx.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                if (action.equals("create user")) {
                    return new EmailAlreadyExistsException("Email is already registered in Keycloak");
                }
                return new KeycloakIntegrationException("Keycloak conflict while trying to " + action, ex);
            }
            return new KeycloakIntegrationException("Keycloak failed to " + action
                    + " (" + responseEx.getStatusCode().value() + ")", ex);
        }
        if (ex instanceof WebClientRequestException) {
            return new KeycloakIntegrationException("Keycloak is unavailable while trying to " + action, ex);
        }
        return new KeycloakIntegrationException("Keycloak call \"" + action + "\" failed", ex);
    }

    private record CachedAdminToken(Mono<String> token, Instant validUntil) {
    }
}
