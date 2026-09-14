package org.km.llmwiki.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Remote Personal Deployment baseline guard (#393 evaluation).
 *
 * <p>Locks the localhost trust boundary the CONDITIONAL GO verdict depends on:
 * the application binds {@code 127.0.0.1} only, and no application-owned
 * Internet security boundary (authentication, session, CSRF, trusted-proxy
 * handling, TLS) exists yet. Any pull request that changes the bind address,
 * introduces such a boundary, or adds Origin checking to {@code /api/v1}
 * must consciously update this guard together with the #393 evaluation —
 * a silent {@code 0.0.0.0} flip fails here first.
 *
 * <p>When the Security adoption issue lands a real Internet boundary, rewrite
 * these assertions into the new contract (bind strategy, session, CSRF,
 * trusted-proxy allowlist); do not silently delete this class.
 */
@Tag("unit")
class RemotePersonalDeploymentBaselineGuardTest {

    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/org/km/llmwiki");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    @Test
    void productionBindRemainsLocalhostOnly() throws Exception {
        String yml = Files.readString(APPLICATION_YML);
        assertThat(yml)
                .as("application.yml must keep the localhost-only bind #393 evaluated")
                .contains("address: 127.0.0.1");
        assertThat(yml)
                .as("direct Internet bind (0.0.0.0) needs a full Security adoption, never a silent flip")
                .doesNotContain("0.0.0.0");
    }

    @Test
    void noApplicationInternetSecurityBoundaryExistsYet() throws Exception {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        List<String> forbidden = List.of(
                "SecurityFilterChain", "EnableWebSecurity", "ForwardedHeaderFilter",
                "CorsConfiguration", "PreAuthorize");
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            for (String token : forbidden) {
                if (text.contains(token)) {
                    offenders.add(source + " contains " + token);
                }
            }
        }
        assertThat(offenders)
                .as("#393 evaluated an application with no auth/session/CSRF/trusted-proxy boundary; "
                        + "the Security adoption must rewrite this guard into the new contract")
                .isEmpty();

        String yml = Files.readString(APPLICATION_YML);
        assertThat(yml)
                .as("no trusted-proxy or TLS contract exists yet; Mode 3 stays REJECT until one lands")
                .doesNotContain("forward-headers")
                .doesNotContain("server.ssl")
                .doesNotContain("ForwardedHeaderFilter");
    }

    @Test
    void originCheckingRemainsMcpLoopbackOnly() throws Exception {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            if (text.contains("\"Origin\"") && !source.toString().contains("/mcp/")) {
                offenders.add(source + " checks Origin outside the mcp package");
            }
        }
        assertThat(offenders)
                .as("only the MCP loopback guard validates Origin; /api/v1 has no Origin/CSRF "
                        + "checking yet (documented gap, #393 §2.2)")
                .isEmpty();

        String guard = Files.readString(
                Path.of("src/main/java/org/km/llmwiki/mcp/McpTransportSecurityGuard.java"));
        assertThat(guard).contains("127.0.0.1").contains("localhost");
    }
}
