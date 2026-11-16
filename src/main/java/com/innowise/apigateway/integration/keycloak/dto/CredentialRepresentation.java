package com.innowise.apigateway.integration.keycloak.dto;

public record CredentialRepresentation(String type, String value, boolean temporary) {

    public static CredentialRepresentation password(String value) {
        return new CredentialRepresentation("password", value, false);
    }
}
