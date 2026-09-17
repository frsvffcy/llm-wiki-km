package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.web.ApiError;
import org.km.llmwiki.web.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class OwnerSecurityContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unauthenticatedMapsToStable401WithoutLeakingExistenceOrSecrets() {
        ResponseEntity<ApiError> response = handler.handleOwnerAuthentication(
                new OwnerAuthenticationException("session abc123 for workspace secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("OWNER_AUTH_REQUIRED");
        assertThat(response.getBody().error().message())
                .isEqualTo("需要擁有者驗證");
        assertThat(response.getBody().toString()).doesNotContain("abc123", "secret");
    }

    @Test
    void hostRejectionMapsToStable403() {
        ResponseEntity<ApiError> response = handler.handleOwnerHostRejected(
                new OwnerHostRejectedException("evil.example with token sk-secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error().code()).isEqualTo("OWNER_HOST_REJECTED");
        assertThat(response.getBody().toString()).doesNotContain("evil.example", "sk-secret");
    }

    @Test
    void originRejectionMapsToStable403() {
        ResponseEntity<ApiError> response = handler.handleOwnerOriginRejected(
                new OwnerOriginRejectedException("https://evil.example"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error().code()).isEqualTo("OWNER_ORIGIN_REJECTED");
        assertThat(response.getBody().toString()).doesNotContain("evil.example");
    }

    @Test
    void rateLimitMapsTo429WithFixedMessage() {
        ResponseEntity<ApiError> response = handler.handleOwnerRateLimited(
                new OwnerRateLimitedException("client 203.0.113.9 throttled"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().error().code()).isEqualTo("OWNER_RATE_LIMITED");
        assertThat(response.getBody().toString()).doesNotContain("203.0.113.9");
    }

    @Test
    void singleUserContractCarriesNoTenantOrRoleConcepts() {
        ResponseEntity<ApiError> response = handler.handleOwnerAuthentication(
                new OwnerAuthenticationException("probe"));

        assertThat(response.getBody().toString())
                .doesNotContainIgnoringCase("tenant", "role", "organization", "rbac");
    }
}
