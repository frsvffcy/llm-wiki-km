package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.web.security.OwnerSecurityProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class DeploymentReadinessServiceTest {

    private static final String HASH =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private static final String REMOTE_HTTP_ORIGIN = "http://100.64.0.5:8766";

    private static OwnerSecurityProperties ownerDisabled() {
        return new OwnerSecurityProperties(false, "", null, null, 0, false,
                null, null, null, null, false, 0, null, 0, null);
    }

    private static OwnerSecurityProperties ownerEnabled() {
        return new OwnerSecurityProperties(true, HASH, null, null, 0, true,
                null, null, null, null, false, 0, null, 0, null);
    }

    private static OwnerSecurityProperties ownerHttpIngress() {
        return new OwnerSecurityProperties(true, HASH, null, null, 0, false,
                null,
                List.of("localhost", "127.0.0.1", "100.64.0.5"),
                List.of("http://localhost:8765", "http://127.0.0.1:8765", REMOTE_HTTP_ORIGIN),
                null, false, 0, null, 0, null);
    }

    @Test
    void localOnlyReportsSupported() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 1, "");

        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, ownerDisabled(), "127.0.0.1", 8765)
                        .current();

        assertThat(readiness.supportState()).isEqualTo(DeploymentReadiness.SUPPORTED);
        assertThat(readiness.mode()).isEqualTo("LOCAL_ONLY");
        assertThat(readiness.backendBind()).isEqualTo("127.0.0.1:8765");
        assertThat(readiness.singleInstance()).isTrue();
    }

    @Test
    void privateIngressWithValidatedBrowserIngressReportsSupported() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:8766"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTP_ORIGIN);

        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, ownerHttpIngress(), "127.0.0.1", 8765)
                        .current();

        assertThat(readiness.supportState()).isEqualTo(DeploymentReadiness.SUPPORTED);
        assertThat(readiness.forwarderBounded()).isTrue();
    }

    @Test
    void privateIngressWithoutBrowserOriginIsNotReady() {
        // #422 §A fail-safe: a private-ingress claim without a validated Browser
        // ingress contract must never report SUPPORTED.
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:8766"),
                "127.0.0.1:8765",
                1,
                "");

        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, ownerEnabled(), "127.0.0.1", 8765)
                        .current();

        assertThat(readiness.supportState()).isEqualTo(DeploymentReadiness.NOT_READY);
    }

    @Test
    void privateIngressWithMismatchedCookieTransportIsNotReady() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:8766"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTP_ORIGIN);

        // http ingress with a Secure cookie could never establish a Browser
        // session, so readiness must stay NOT_READY instead of SUPPORTED.
        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, ownerEnabled(), "127.0.0.1", 8765)
                        .current();

        assertThat(readiness.supportState()).isEqualTo(DeploymentReadiness.NOT_READY);
    }

    @Test
    void privateIngressWithLocalhostOnlyAllowlistIsNotReady() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:8766"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTP_ORIGIN);

        // Cookie transport matches (plain http), but the owner allowlists never
        // learned the remote ingress host/origin, so readiness stays NOT_READY.
        OwnerSecurityProperties localhostCookieProfile = new OwnerSecurityProperties(true, HASH,
                null, null, 0, false, null, null, null, null, false, 0, null, 0, null);
        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, localhostCookieProfile, "127.0.0.1", 8765)
                        .current();

        assertThat(readiness.supportState()).isEqualTo(DeploymentReadiness.NOT_READY);
    }

    @Test
    void reverseProxyIsNeverSupported() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.REVERSE_PROXY_CANDIDATE,
                List.of("100.64.0.5:443"), "127.0.0.1:8765", 1, "");

        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, ownerEnabled(), "127.0.0.1", 8765)
                        .current();

        assertThat(readiness.supportState()).isEqualTo(DeploymentReadiness.CANDIDATE);
    }

    @Test
    void invalidProfileReportsNotReadyInsteadOfThrowing() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS, List.of(), "127.0.0.1:8765", 1, "");

        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, ownerEnabled(), "127.0.0.1", 8765)
                        .current();

        assertThat(readiness.supportState()).isEqualTo(DeploymentReadiness.NOT_READY);
    }

    @Test
    void projectionNeverExposesSecretsPathsOrBackendIdentities() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:8766"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTP_ORIGIN);

        DeploymentReadiness readiness =
                new DeploymentReadinessService(properties, ownerHttpIngress(), "127.0.0.1", 8765)
                        .current();

        String rendered = readiness.mode() + readiness.supportState()
                + readiness.backendBind() + readiness.reason();
        assertThat(rendered)
                .doesNotContain("sk-", "Bearer", "password", "token", "RID", "jdbc:", "/vault");
    }
}
