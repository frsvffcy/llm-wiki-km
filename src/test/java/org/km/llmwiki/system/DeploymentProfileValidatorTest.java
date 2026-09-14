package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.web.security.OwnerSecurityProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class DeploymentProfileValidatorTest {

    private static final String HASH =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private static final String REMOTE_HOST = "100.64.0.5";
    private static final String REMOTE_HTTP_ORIGIN = "http://100.64.0.5:8766";
    private static final String REMOTE_HTTPS_ORIGIN = "https://100.64.0.5:8443";

    private static OwnerSecurityProperties ownerDisabled() {
        return new OwnerSecurityProperties(false, "", null, null, 0, false,
                null, null, null, null, false, 0, null, 0, null);
    }

    private static OwnerSecurityProperties ownerEnabled() {
        return new OwnerSecurityProperties(true, HASH, null, null, 0, true,
                null, null, null, null, false, 0, null, 0, null);
    }

    /** Fully aligned http-over-encrypted-tunnel owner profile (#422 §C). */
    private static OwnerSecurityProperties ownerHttpIngress() {
        return new OwnerSecurityProperties(true, HASH, null, null, 0, false,
                null,
                List.of("localhost", "127.0.0.1", REMOTE_HOST),
                List.of("http://localhost:8765", "http://127.0.0.1:8765", REMOTE_HTTP_ORIGIN),
                null, false, 0, null, 0, null);
    }

    /** Fully aligned private-TLS owner profile (#422 §C, preferred). */
    private static OwnerSecurityProperties ownerHttpsIngress() {
        return new OwnerSecurityProperties(true, HASH, null, null, 0, true,
                null,
                List.of("localhost", "127.0.0.1", REMOTE_HOST),
                List.of("http://localhost:8765", "http://127.0.0.1:8765", REMOTE_HTTPS_ORIGIN),
                null, false, 0, null, 0, null);
    }

    private static DeploymentProperties localOnly() {
        return new DeploymentProperties(DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 1, "");
    }

    private static DeploymentProperties privateIngress(String... binds) {
        return new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS, List.of(binds), "127.0.0.1:8765", 1, "");
    }

    private static DeploymentProperties privateIngressHttp() {
        return new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:8766"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTP_ORIGIN);
    }

    private static DeploymentProperties privateIngressHttps() {
        return new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:8443"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTPS_ORIGIN);
    }

    @Test
    void localOnlyBaselineValidates() {
        new DeploymentProfileValidator(localOnly(), ownerDisabled(), "127.0.0.1", 8765).validate();
    }

    @Test
    void localOnlyWithBrowserOriginFailsFast() {
        DeploymentProperties mixed = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 1, REMOTE_HTTP_ORIGIN);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        mixed, ownerDisabled(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("browser origin");
    }

    @Test
    void nonLoopbackBackendBindFailsFast() {
        DeploymentProperties properties = localOnly();

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        properties, ownerDisabled(), "0.0.0.0", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void privateIngressRequiresExplicitForwarderScope() {
        DeploymentProperties missing = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS, List.of(), "127.0.0.1:8765", 1, REMOTE_HTTP_ORIGIN);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        missing, ownerHttpIngress(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forwarder scope");
    }

    @Test
    void privateIngressRequiresBrowserOrigin() {
        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngress("100.64.0.5:8766"), ownerHttpIngress(), "127.0.0.1", 8765)
                        .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("browser origin");
    }

    @Test
    void wildcardForwarderBindFailsFast() {
        for (String wildcard : List.of("0.0.0.0", "0.0.0.0:8766", "::", "*", "*:8766")) {
            DeploymentProperties properties = new DeploymentProperties(
                    DeploymentMode.PRIVATE_INGRESS, List.of(wildcard), "127.0.0.1:8765", 1,
                    REMOTE_HTTP_ORIGIN);

            assertThatThrownBy(() -> new DeploymentProfileValidator(
                            properties, ownerHttpIngress(), "127.0.0.1", 8765).validate())
                    .as("wildcard bind %s must fail closed", wildcard)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("all interfaces");
        }
    }

    @Test
    void forwarderBindWithoutPortFailsFast() {
        DeploymentProperties bare = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS, List.of("100.64.0.5"), "127.0.0.1:8765", 1,
                REMOTE_HTTP_ORIGIN);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        bare, ownerHttpIngress(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host and port");
    }

    @Test
    void privateIngressRequiresOwnerAuthentication() {
        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngressHttp(), ownerDisabled(), "127.0.0.1", 8765)
                        .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner authentication");
    }

    @Test
    void privateIngressWithValidatedHttpProfileValidates() {
        new DeploymentProfileValidator(
                        privateIngressHttp(), ownerHttpIngress(), "127.0.0.1", 8765)
                .validate();
    }

    @Test
    void privateIngressWithValidatedHttpsProfileValidates() {
        new DeploymentProfileValidator(
                        privateIngressHttps(), ownerHttpsIngress(), "127.0.0.1", 8765)
                .validate();
    }

    @Test
    void loopbackBrowserOriginFailsFast() {
        DeploymentProperties loopback = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("127.0.0.1:8766"),
                "127.0.0.1:8765",
                1,
                "http://localhost:8766");
        OwnerSecurityProperties owner = new OwnerSecurityProperties(true, HASH,
                null, null, 0, false, null,
                List.of("localhost", "127.0.0.1"),
                List.of("http://localhost:8766", "http://127.0.0.1:8766"),
                null, false, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        loopback, owner, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-loopback");
    }

    @Test
    void malformedBrowserOriginFailsFastWithoutEcho() {
        for (String malformed : List.of(
                "http://user@100.64.0.5:8766",
                "http://100.64.0.5:8766/path",
                "ftp://100.64.0.5:8766",
                "http://*:8766",
                "http://0.0.0.0:8766")) {
            DeploymentProperties properties = new DeploymentProperties(
                    DeploymentMode.PRIVATE_INGRESS,
                    List.of("100.64.0.5:8766"), "127.0.0.1:8765", 1, malformed);

            assertThatThrownBy(() -> new DeploymentProfileValidator(
                            properties, ownerHttpIngress(), "127.0.0.1", 8765).validate())
                    .as("malformed browser origin %s must fail closed", malformed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("browser origin");
        }
    }

    @Test
    void ownerHostAllowlistMustAcceptBrowserHost() {
        OwnerSecurityProperties localhostOnly = new OwnerSecurityProperties(true, HASH,
                null, null, 0, false, null, null, null, null, false, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngressHttp(), localhostOnly, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowlists");
    }

    @Test
    void ownerOriginAllowlistMustAcceptBrowserOrigin() {
        OwnerSecurityProperties hostOnly = new OwnerSecurityProperties(true, HASH,
                null, null, 0, false, null,
                List.of("localhost", "127.0.0.1", REMOTE_HOST),
                List.of("http://localhost:8765", "http://127.0.0.1:8765"),
                null, false, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngressHttp(), hostOnly, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowlists");
    }

    @Test
    void forwarderPortMustMatchBrowserPort() {
        DeploymentProperties mismatched = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:9999"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTP_ORIGIN);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        mismatched, ownerHttpIngress(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ingress port");
    }

    @Test
    void ipIngressAddressMustMatchForwarderHost() {
        DeploymentProperties mismatched = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("192.168.1.10:8766"),
                "127.0.0.1:8765",
                1,
                REMOTE_HTTP_ORIGIN);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        mismatched, ownerHttpIngress(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ingress address");
    }

    @Test
    void dnsIngressRequiresOnlyPortMatch() {
        DeploymentProperties dns = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("100.64.0.5:443"),
                "127.0.0.1:8765",
                1,
                "https://wiki.internal.example");
        OwnerSecurityProperties owner = new OwnerSecurityProperties(true, HASH,
                null, null, 0, true, null,
                List.of("localhost", "127.0.0.1", "wiki.internal.example"),
                List.of("http://localhost:8765", "https://wiki.internal.example"),
                null, false, 0, null, 0, null);

        new DeploymentProfileValidator(dns, owner, "127.0.0.1", 8765).validate();
    }

    @Test
    void httpIngressWithSecureCookieFailsFast() {
        OwnerSecurityProperties secure = new OwnerSecurityProperties(true, HASH,
                null, null, 0, true, null,
                List.of("localhost", "127.0.0.1", REMOTE_HOST),
                List.of("http://localhost:8765", REMOTE_HTTP_ORIGIN),
                null, false, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngressHttp(), secure, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie");
    }

    @Test
    void httpsIngressWithoutSecureCookieFailsFast() {
        OwnerSecurityProperties plain = new OwnerSecurityProperties(true, HASH,
                null, null, 0, false, null,
                List.of("localhost", "127.0.0.1", REMOTE_HOST),
                List.of("http://localhost:8765", REMOTE_HTTPS_ORIGIN),
                null, false, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngressHttps(), plain, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie");
    }

    @Test
    void wildcardAllowlistEntriesCanNeverSatisfyBrowserIngress() {
        // Exact-match semantics: a wildcard entry matches nothing, so the
        // profile fails closed instead of opening the ingress.
        OwnerSecurityProperties wildcard = new OwnerSecurityProperties(true, HASH,
                null, null, 0, false, null,
                List.of("*"),
                List.of("*"),
                null, false, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngressHttp(), wildcard, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowlists");
    }

    @Test
    void proxyTrustWithoutPeersFailsFast() {
        OwnerSecurityProperties trusting = new OwnerSecurityProperties(true, HASH,
                null, null, 0, false, null,
                List.of("localhost", "127.0.0.1", REMOTE_HOST),
                List.of("http://localhost:8765", REMOTE_HTTP_ORIGIN),
                null, true, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngressHttp(), trusting, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted proxies");
    }

    @Test
    void forwarderTargetMustStayLoopbackBackend() {
        DeploymentProperties offTarget = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS, List.of("100.64.0.5:8766"), "192.168.1.10:8765", 1,
                REMOTE_HTTP_ORIGIN);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        offTarget, ownerHttpIngress(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback backend");
    }

    @Test
    void multiInstanceFailsFast() {
        DeploymentProperties multi = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 2, "");

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        multi, ownerDisabled(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single instance");
    }

    @Test
    void localOnlyWithForwarderScopeFailsFast() {
        DeploymentProperties mixed = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of("100.64.0.5:8766"), "127.0.0.1:8765", 1, "");

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        mixed, ownerDisabled(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-only");
    }

    @Test
    void validationIsRepeatableForRestart() {
        DeploymentProfileValidator validator = new DeploymentProfileValidator(
                privateIngressHttp(), ownerHttpIngress(), "127.0.0.1", 8765);

        validator.validate();
        validator.validate();
    }
}
