# apiGateway

API Gateway for jACApp — single entry point for the whole system.

Stack: **Spring Cloud Gateway (WebFlux)** + Spring Boot 4.1.1 / Java 21.

## Responsibilities

- Routes all client traffic to the downstream services (`authService`/Keycloak, `userService`).
- Checks the JWT access token on every endpoint except **Login** and **Register**.
- Custom registration flow: credentials go to the Authentication Service (Keycloak),
  profile data goes to the User Service (`POST /api/internal/users`); the gateway
  compensates (deletes the Keycloak user) if the profile write fails.

## Registration (canonical client path)

**`POST /api/auth/register`** — the only registration path clients should use.
It is handled by the gateway itself (not routed downstream); the gateway orchestrates
both services:

1. **Authentication Service** (Keycloak Admin API): user + `USER` role are created;
   if the role assignment fails, the created user is deleted.
2. **User Service**: `POST /api/internal/users` (internal API key header) saves the profile
   under the Keycloak-generated id. This endpoint is not routed through the gateway and is
   hidden from Swagger.
3. If step 2 fails (409/500/timeout), the gateway **compensates** by deleting the Keycloak
   user — a ghost account is left behind only if the compensating delete itself fails.

Response: **201** with the saved profile (the User Service response body is passed through).

The gateway also routes `POST /api/users` to the User Service — that is the service's own
endpoint (it talks to Keycloak itself). It is **not** the intended client registration path.

## Build

```bash
./mvnw clean package        # Linux/macOS
mvnw.cmd clean package      # Windows
```

## Run (local)

```bash
mvnw.cmd spring-boot:run    # profile: local
```

## Run (docker)

Built and started by `docker-compose.yaml` in this repository (profile: docker,
service `api-gateway`, port 8080). The compose also builds `../authService` and
`../userService` — the apiGateway clone must sit next to jACApp.

## Status

Implemented: routes (login proxy, `/api/users/**`, swagger proxy), reactive JWT security,
the custom registration flow with compensation, and tests for the whole flow
(`RegistrationFlowTest`, WebTestClient with mocked downstream clients).
