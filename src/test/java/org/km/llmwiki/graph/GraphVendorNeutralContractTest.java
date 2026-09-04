package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class GraphVendorNeutralContractTest {

    private static final List<String> FORBIDDEN_PRODUCTION_REFERENCES = List.of(
            "arcadedb", "neo4j", "ryugraph", "bigquery", "spanner", "cypher", "gql", "sql-pgq",
            "rid", "vendor node", "vendor edge", "vendor record");

    @Test
    void graphProductionContractContainsNoVendorApiOrQueryReference() throws IOException {
        Path sourceRoot = Path.of("src/main/java/org/km/llmwiki/graph");
        assertThat(Files.isDirectory(sourceRoot)).isTrue();

        try (var paths = Files.walk(sourceRoot)) {
            List<String> productionSources = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .map(this::readLowerCase)
                    .toList();

            assertThat(productionSources).isNotEmpty();
            for (String source : productionSources) {
                for (String forbiddenReference : FORBIDDEN_PRODUCTION_REFERENCES) {
                    assertThat(source)
                            .as("graph production source must remain vendor-neutral: %s",
                                    forbiddenReference)
                            .doesNotContain(forbiddenReference);
                }
            }
        }
    }

    private String readLowerCase(Path path) {
        try {
            String source = Files.readString(path)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?m)//.*$", " ")
                    .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                    .replaceAll("'(?:\\\\.|[^'\\\\])*'", "''");
            return source.toLowerCase(Locale.ROOT);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read graph production source", exception);
        }
    }
}
