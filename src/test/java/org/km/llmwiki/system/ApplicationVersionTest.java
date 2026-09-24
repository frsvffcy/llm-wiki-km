package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Single version-truth guard (Refs #456 R1, challenge case 1).
 *
 * <p>{@code pom.xml} {@code project.version} is the only authority; the
 * runtime version must follow it without a manual Java-literal sync, so a
 * Maven version bump can never keep serving the old version after a rebuild.
 */
@Tag("unit")
class ApplicationVersionTest {

    /** pom.xml direct-child project version (never the parent starter version). */
    static String mavenProjectVersion() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var root = document.getDocumentElement();
        var children = root.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            var node = children.item(index);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && "version".equals(node.getNodeName())) {
                return node.getTextContent().strip();
            }
        }
        throw new IllegalStateException("pom.xml project version not found");
    }

    @Test
    void runtimeVersionEqualsMavenProjectVersion() throws Exception {
        String expected = mavenProjectVersion();
        assertThat(expected).matches("[0-9]+\\.[0-9]+\\.[0-9]+.*");
        assertThat(new ApplicationVersion().version()).isEqualTo(expected);
    }

    @Test
    void filteredBuildMetadataEqualsMavenProjectVersion() throws Exception {
        String expected = mavenProjectVersion();
        Path filtered = Path.of("target/classes/version.properties");
        assertThat(filtered).as("mvn process-resources must filter version.properties").isRegularFile();
        Properties properties = new Properties();
        try (var stream = Files.newInputStream(filtered)) {
            properties.load(stream);
        }
        assertThat(properties.getProperty("app.version")).isEqualTo(expected);
    }

    @Test
    void sourceAuthorityIsFilteredPlaceholder() throws Exception {
        String source = Files.readString(Path.of("src/main/resources/version.properties"));
        assertThat(source).contains("app.version=@project.version@");
    }

    @Test
    void rejectsUnfilteredOrBlankVersions() {
        assertThatThrownBy(() -> new ApplicationVersion("@project.version@"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApplicationVersion("  "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApplicationVersion(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
