package com.innowise.apigateway.service;

import com.innowise.apigateway.integration.keycloak.KeycloakAdminClient;
import com.innowise.apigateway.integration.userservice.UserServiceClient;
import com.innowise.apigateway.integration.userservice.dto.UserProfileRequest;
import com.innowise.apigateway.model.dto.RegisterRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final KeycloakAdminClient keycloakAdminClient;
    private final UserServiceClient userServiceClient;

    public Mono<JsonNode> register(RegisterRequestDto dto) {
        return keycloakAdminClient.createUser(
                        dto.getEmail(), dto.getPassword(), dto.getName(), dto.getSurname())
                .flatMap(keycloakId -> userServiceClient
                        .createProfile(UserProfileRequest.from(keycloakId, dto))
                        .map(profile -> new ProfileResult(keycloakId, profile))
                        .onErrorResume(profileEx -> compensate(keycloakId).then(Mono.error(profileEx))))
                .map(ProfileResult::profile);
    }

    private Mono<Void> compensate(UUID keycloakId) {
        return keycloakAdminClient.deleteUser(keycloakId)
                .onErrorResume(compensationEx -> {
                    log.error("Ghost account: Keycloak user {} remains without a local profile "
                            + "because the compensating delete failed", keycloakId, compensationEx);
                    return Mono.empty();
                });
    }

    /** Keeps the keycloak id alive for the compensating delete while the profile is streamed back. */
    private record ProfileResult(UUID keycloakId, JsonNode profile) {
    }
}
