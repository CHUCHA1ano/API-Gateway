package com.innowise.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "keycloak.admin")
public record KeycloakProperties(
        String url,
        String realm,
        String clientId,
        String clientSecret
) {
}
