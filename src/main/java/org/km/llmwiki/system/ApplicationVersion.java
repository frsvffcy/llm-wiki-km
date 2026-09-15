package org.km.llmwiki.system;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single runtime version authority (Refs #456 R1).
 *
 * <p>The version is generated at build time only: Maven resource filtering
 * writes {@code pom.xml} {@code project.version} into
 * {@code version.properties} ({@code app.version}). There is no second
 * hardcoded Java literal to keep in sync on release bumps.
 *
 * <p>When running from the packaged JAR, the Spring Boot manifest
 * {@code Implementation-Version} (likewise derived from
 * {@code project.version}) must agree with the bundled properties; any
 * divergence fails fast instead of serving a stale version. On an exploded
 * classpath (tests, IDE) the manifest is absent and the check is skipped.
 */
@Component
public class ApplicationVersion {

    private final String version;

    public ApplicationVersion() {
        this(loadBundledVersion());
    }

    ApplicationVersion(String version) {
        this.version = requireValid(version);
    }

    public String version() {
        return version;
    }

    private static String loadBundledVersion() {
        Properties properties = new Properties();
        try (InputStream stream = ApplicationVersion.class.getResourceAsStream("/version.properties")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "version.properties missing from classpath; build with Maven "
                                + "so pom.xml project.version is filtered in (Refs #456 R1)");
            }
            properties.load(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read version.properties", failure);
        }
        String bundled = properties.getProperty("app.version");
        String valid = requireValid(bundled);
        String manifest = ApplicationVersion.class.getPackage().getImplementationVersion();
        if (manifest != null && !manifest.equals(valid)) {
            throw new IllegalStateException(
                    "packaged version mismatch: version.properties=" + valid
                            + " manifest Implementation-Version=" + manifest);
        }
        return valid;
    }

    private static String requireValid(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    "app.version missing; version.properties must be Maven-filtered "
                            + "from pom.xml project.version (Refs #456 R1)");
        }
        String trimmed = version.strip();
        if (trimmed.contains("@")) {
            throw new IllegalStateException(
                    "app.version is unfiltered (" + trimmed + "); build with Maven "
                            + "so @project.version@ is replaced (Refs #456 R1)");
        }
        if (!trimmed.matches("[0-9A-Za-z][0-9A-Za-z._-]*")) {
            throw new IllegalStateException("app.version has an unexpected shape: " + trimmed);
        }
        return trimmed;
    }
}
