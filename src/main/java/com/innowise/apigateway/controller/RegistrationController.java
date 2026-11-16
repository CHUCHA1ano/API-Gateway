package com.innowise.apigateway.controller;

import com.innowise.apigateway.model.dto.RegisterRequestDto;
import com.innowise.apigateway.service.RegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Custom registration endpoint. It is not routed downstream: the gateway itself
 * orchestrates Authentication Service and User Service (see {@code RegistrationService}).
 * The response body is the profile saved by the User Service (201), returned to the
 * client without a second request.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JsonNode> register(@Valid @RequestBody RegisterRequestDto dto) {
        return registrationService.register(dto);
    }
}
