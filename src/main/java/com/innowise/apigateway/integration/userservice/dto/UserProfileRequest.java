package com.innowise.apigateway.integration.userservice.dto;

import com.innowise.apigateway.model.dto.RegisterRequestDto;

import java.util.UUID;

public record UserProfileRequest(
        UUID keycloakId,
        String name,
        String surname,
        String birthDate,
        String email
) {

    public static UserProfileRequest from(UUID keycloakId, RegisterRequestDto dto) {
        return new UserProfileRequest(
                keycloakId,
                dto.getName(),
                dto.getSurname(),
                dto.getBirthDate().toString(),
                dto.getEmail());
    }
}
