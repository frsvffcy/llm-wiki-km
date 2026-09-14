package org.km.llmwiki.system;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Explicit single-instance deployment profile (#418, browser-origin contract #422).
 *
 * <p>The raw application listener stays loopback-only (see {@code server.address});
 * remote traffic may only arrive via bounded host-local forwarding to that
 * backend. {@code forwarderBinds} declares the forwarder listen scope: every
 * entry is a {@code host} or {@code host:port} value that must be explicit and
 * must never be a wildcard bind. {@code forwarderTarget} must always resolve to
 * the loopback backend. {@code maxInstances} is locked to {@code 1}: a second
 * writer or a multi-replica shared writable state is not supported.
 *
 * <p>{@code browserOrigin} is the single canonical external Browser ingress
 * truth for non-local modes (#422 §B): {@code scheme://host[:port]}. Host and
 * Origin allowlists plus the forwarder scope are cross-validated against it, so
 * the three lists cannot drift. {@code LOCAL_ONLY} must leave it empty; pure
 * SSH-tunnel access keeps {@code LOCAL_ONLY} with owner authentication and a
 * localhost Browser origin instead of declaring a private ingress.
 *
 * <p>Binding fails fast when the profile is inconsistent; see
 * {@link DeploymentProfileValidator}.
 */
@ConfigurationProperties("app.deployment")
public record DeploymentProperties(
        DeploymentMode mode,
        List<String> forwarderBinds,
        String forwarderTarget,
        int maxInstances,
        String browserOrigin) {

    public DeploymentProperties {
        if (mode == null) {
            mode = DeploymentMode.LOCAL_ONLY;
        }
        if (forwarderBinds == null) {
            forwarderBinds = List.of();
        }
        if (forwarderTarget == null || forwarderTarget.isBlank()) {
            forwarderTarget = "127.0.0.1:8765";
        }
        if (maxInstances <= 0) {
            maxInstances = 1;
        }
        if (browserOrigin == null) {
            browserOrigin = "";
        }
        forwarderBinds = List.copyOf(forwarderBinds.stream()
                .filter(value -> value != null)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList());
        forwarderTarget = forwarderTarget.strip();
        browserOrigin = browserOrigin.strip();
    }
}
