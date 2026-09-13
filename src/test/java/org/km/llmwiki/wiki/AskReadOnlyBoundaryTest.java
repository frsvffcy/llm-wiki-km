package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ask read-only regression (#374): the Ask execution path must never reference the
 * proposal ingress or persistence — the governed mutation is a separate, explicitly
 * invoked command. Source-level boundary check mirrors the Browser no-innerHTML tests.
 */
@Tag("unit")
class AskReadOnlyBoundaryTest {

    @Test
    void askExecutionPathNeverReferencesProposalIngressOrPersistence() throws Exception {
        assertThat(sourceOf("src/main/java/org/km/llmwiki/ai/ask/AskService.java"))
                .doesNotContain("AskProposalIngress", "KnowledgeProposalRepository",
                        "AskProposalIngressRepository");
        assertThat(sourceOf("src/main/java/org/km/llmwiki/ai/ask/AskApplicationService.java"))
                .doesNotContain("AskProposalIngress", "KnowledgeProposalRepository",
                        "AskProposalIngressRepository");
        assertThat(sourceOf("src/main/java/org/km/llmwiki/ai/answer/AnswerClient.java"))
                .doesNotContain("AskProposalIngress");
    }

    private String sourceOf(String path) throws Exception {
        String source = Files.readString(Path.of(path));
        assertThat(source).isNotBlank();
        return source;
    }
}
