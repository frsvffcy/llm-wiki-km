package org.km.llmwiki.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class DocumentAnalysisPromptProvisionerTest {

    private final DocumentAnalysisPromptProvisioner provisioner =
            new DocumentAnalysisPromptProvisioner();

    @TempDir
    Path temporaryDirectory;

    @Test
    void provisionsVersionedDefaultPromptForFreshWorkspace() throws Exception {
        Path root = temporaryDirectory.resolve("fresh-root");

        boolean provisioned = provisioner.provisionIfMissing(root);

        assertThat(provisioned).isTrue();
        Path prompt = root.resolve("config/prompts/document-analysis.md");
        assertThat(prompt).isRegularFile();
        String content = Files.readString(prompt);
        assertThat(content).contains("<!-- prompt-version: v1 -->");
        assertThat(content).contains("{{document.metadata}}");
        assertThat(content).contains("{{evidence}}");
        assertThat(content).doesNotContain("sk-", "apiKey", "Authorization");
    }

    @Test
    void neverOverwritesAnExistingUserPrompt() throws Exception {
        Path root = temporaryDirectory.resolve("existing-root");
        Path prompt = root.resolve("config/prompts/document-analysis.md");
        Files.createDirectories(prompt.getParent());
        Files.writeString(prompt, """
                <!-- prompt-version: custom -->
                {{document.metadata}}
                {{evidence}}
                使用者自訂內容
                """);

        boolean provisioned = provisioner.provisionIfMissing(root);

        assertThat(provisioned).isFalse();
        assertThat(Files.readString(prompt)).contains("使用者自訂內容");
        assertThat(Files.readString(prompt)).doesNotContain("文件分析助手");
    }

    @Test
    void provisionsAgainOnlyWhenThePromptFileIsAbsent() throws Exception {
        Path root = temporaryDirectory.resolve("recovery-root");

        assertThat(provisioner.provisionIfMissing(root)).isTrue();
        assertThat(provisioner.provisionIfMissing(root)).isFalse();

        Files.delete(root.resolve("config/prompts/document-analysis.md"));

        assertThat(provisioner.provisionIfMissing(root)).isTrue();
        assertThat(root.resolve("config/prompts/document-analysis.md")).isRegularFile();
    }
}
