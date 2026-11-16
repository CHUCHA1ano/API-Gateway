package com.innowise.apigateway;

import com.innowise.apigateway.exception.EmailAlreadyExistsException;
import com.innowise.apigateway.exception.UserServiceIntegrationException;
import com.innowise.apigateway.integration.keycloak.KeycloakAdminClient;
import com.innowise.apigateway.integration.userservice.UserServiceClient;
import com.innowise.apigateway.integration.userservice.dto.UserProfileRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the custom registration flow orchestrated by the gateway
 * ({@code POST /api/auth/register}) end-to-end with the two downstream clients mocked:
 * <ul>
 *   <li>happy path — 201 and the User Service profile passed through as is;</li>
 *   <li>profile write fails (409/500) — the Keycloak user is compensated (rollback of the saga);</li>
 *   <li>Keycloak itself rejects the creation — no compensation, no profile call;</li>
 *   <li>request validation and the JWT requirement on the remaining endpoints.</li>
 * </ul>
 * Both {@code keycloak.admin.client-secret} and {@code user-service.internal-api-key} must be
 * provided here: the corresponding properties have no defaults by design (fail-fast on a
 * missing secret), so the test context stubs them with test values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RegistrationFlowTest {

    private static final String REGISTER_BODY = """
            {
              "name": "John",
              "surname": "Doe",
              "birthDate": "1990-01-01",
              "email": "john@example.com",
              "password": "password123"
            }""";

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    @MockitoBean
    private UserServiceClient userServiceClient;

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void stubSecrets(DynamicPropertyRegistry registry) {
        registry.add("keycloak.admin.client-secret", () -> "test-client-secret");
        registry.add("user-service.internal-api-key", () -> "test-internal-key");
    }

    @Test
    void register_createsKeycloakUserAndProfile_returnsProfileWith201() {
        UUID keycloakId = UUID.randomUUID();
        JsonNode profile = profile(keycloakId);

        when(keycloakAdminClient.createUser(eq("john@example.com"), eq("password123"),
                eq("John"), eq("Doe"))).thenReturn(Mono.just(keycloakId));
        when(userServiceClient.createProfile(any(UserProfileRequest.class)))
                .thenReturn(Mono.just(profile));

        client.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(REGISTER_BODY)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .json("{\"id\":\"" + keycloakId + "\",\"email\":\"john@example.com\",\"active\":true}");

        // The profile must be saved under the Keycloak-generated id, not under anything
        // derived from the request.
        ArgumentCaptor<UserProfileRequest> captor = ArgumentCaptor.forClass(UserProfileRequest.class);
        verify(userServiceClient).createProfile(captor.capture());
        assertEquals(keycloakId, captor.getValue().keycloakId());
        assertEquals("john@example.com", captor.getValue().email());
        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    void register_profileConflict_compensatesByDeletingKeycloakUser_returns409() {
        UUID keycloakId = UUID.randomUUID();
        when(keycloakAdminClient.createUser(any(), any(), any(), any()))
                .thenReturn(Mono.just(keycloakId));
        when(userServiceClient.createProfile(any(UserProfileRequest.class)))
                .thenReturn(Mono.error(new EmailAlreadyExistsException(
                        "Email is already registered in User Service")));
        when(keycloakAdminClient.deleteUser(keycloakId)).thenReturn(Mono.empty());

        client.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(REGISTER_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        // Saga rollback: the Keycloak account must not outlive the failed profile write.
        verify(keycloakAdminClient).deleteUser(keycloakId);
    }

    @Test
    void register_profileWriteFails_compensatesByDeletingKeycloakUser_returns502() {
        UUID keycloakId = UUID.randomUUID();
        when(keycloakAdminClient.createUser(any(), any(), any(), any()))
                .thenReturn(Mono.just(keycloakId));
        when(userServiceClient.createProfile(any(UserProfileRequest.class)))
                .thenReturn(Mono.error(new UserServiceIntegrationException(
                        "User Service failed to save the profile (500)",
                        new RuntimeException("boom"))));
        when(keycloakAdminClient.deleteUser(keycloakId)).thenReturn(Mono.empty());

        client.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(REGISTER_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        verify(keycloakAdminClient).deleteUser(keycloakId);
    }

    @Test
    void register_keycloakConflict_returns409_withoutCompensationOrProfileCall() {
        when(keycloakAdminClient.createUser(any(), any(), any(), any()))
                .thenReturn(Mono.error(new EmailAlreadyExistsException(
                        "Email is already registered in Keycloak")));

        client.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(REGISTER_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        // Nothing was created downstream, so there is nothing to compensate.
        verify(keycloakAdminClient, never()).deleteUser(any());
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void register_invalidRequest_returns400WithFieldErrors() {
        String invalidBody = """
                {
                  "name": "John",
                  "surname": "Doe",
                  "birthDate": "1990-01-01",
                  "email": "not-an-email",
                  "password": "short"
                }""";

        client.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalidBody)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.email").isEqualTo("Email must be valid")
                .jsonPath("$.errors.password").isEqualTo("Password must contain from 8 to 72 characters");

        verifyNoInteractions(keycloakAdminClient, userServiceClient);
    }

    @Test
    void protectedEndpoints_withoutJwt_return401() {
        client.get().uri("/api/users")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private JsonNode profile(UUID id) {
        return JsonMapper.builder().build().readTree(
                "{\"id\":\"" + id + "\",\"email\":\"john@example.com\",\"name\":\"John\",\"active\":true}");
    }
}
