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
 * Remote Personal Deployment security-boundary guard (#393 evaluation, #417 adoption).
 *
 * <p>Locks the invariants the CONDITIONAL GO verdict depends on: the
 * application binds {@code 127.0.0.1} only, the single-user owner security
 * boundary stays application-owned and bounded (no framework security chain,
 * no multi-user schema, no silent {@code 0.0.0.0} flip), and {@code Origin}
 * checking exists exactly in the MCP loopback guard and the owner
 * {@code /api/v1} boundary — nowhere else.
 *
 * <p>Rewritten by #417 from the pre-adoption tripwire (which asserted that no
 * application Internet boundary existed) into the adoption contract. Any pull
 * request that changes the bind address, widens the boundary, or adds Origin
 * checking elsewhere must consciously update this guard together with the
 * #393 evaluation — a silent exposure flip fails here first.
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
    void ownerBoundaryStaysApplicationOwnedWithoutFrameworkSecurityChain() throws Exception {
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
                .as("#417 keeps the owner boundary application-owned: no framework security "
                        + "chain, no trusted-proxy filter, no framework cross-origin config")
                .isEmpty();
    }

    @Test
    void ownerBoundaryHasNoMultiUserSchema() throws Exception {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            if (!source.toString().contains("/web/security/")) {
                continue;
            }
            String text = filesText(source);
            for (String token : List.of("tenantId", "TenantRecord", "organizationId",
                    "OrganizationRecord", "GrantedAuthority", "UserDetails")) {
                if (text.contains(token)) {
                    offenders.add(source + " contains " + token);
                }
            }
        }
        assertThat(offenders)
                .as("single-user owner boundary must not grow tenant/organization/role schema")
                .isEmpty();

        String yml = Files.readString(APPLICATION_YML);
        assertThat(yml)
                .as("local-only stays the default; remote protection is opt-in")
                .contains("auth-enabled: ${OWNER_AUTH_ENABLED:false}");
        assertThat(yml)
                .as("the default bind strategy never changes with the owner boundary")
                .contains("address: 127.0.0.1");
    }

    private static String filesText(Path source) throws Exception {
        return Files.readString(source);
    }

    @Test
    void originCheckingRemainsScopedToMcpAndOwnerBoundary() throws Exception {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            boolean scoped = source.toString().contains("/mcp/")
                    || source.toString().contains("/web/security/");
            if (text.contains("\"Origin\"") && !scoped) {
                offenders.add(source + " checks Origin outside the mcp/owner boundary");
            }
        }
        assertThat(offenders)
                .as("only the MCP loopback guard and the owner /api/v1 boundary validate "
                        + "Origin (#393 §2.2 gap closed by #417 for the owner surface only)")
                .isEmpty();

        String guard = Files.readString(
                Path.of("src/main/java/org/km/llmwiki/mcp/McpTransportSecurityGuard.java"));
        assertThat(guard).contains("127.0.0.1").contains("localhost");

        String filter = Files.readString(
                Path.of("src/main/java/org/km/llmwiki/web/security/OwnerSecurityFilter.java"));
        assertThat(filter).contains("OWNER_ORIGIN_REJECTED");
    }
}
