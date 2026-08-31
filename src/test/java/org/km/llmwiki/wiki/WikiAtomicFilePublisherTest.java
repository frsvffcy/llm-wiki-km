package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.LlmProposalAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class WikiAtomicFilePublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void cleansStagedFileAndLeavesNoTargetWhenAtomicCommitFails() throws Exception {
        AtomicWikiFileCommitter failingCommitter = (staged, target) -> {
            throw new IOException("simulated atomic commit failure");
        };
        WikiAtomicFilePublisher publisher = new WikiAtomicFilePublisher(failingCommitter,
                new WikiPublishedMarkdownValidator());
        WikiDraft draft = draft();
        String content = new WikiPublishedMarkdownRenderer(new WikiDraftMarkdownRenderer())
                .render(draft, "wiki:atomic-failure", 13L, 1, "2026-08-29T00:00:00Z");
        String hash = WikiContentHash.sha256(content);
        Path target = tempDir.resolve("atomic-failure.md");
        StagedWikiFile staged = publisher.stage(target, content, hash, draft);
        assertThat(staged.temporaryPath()).exists();

        assertThatThrownBy(() -> publisher.commit(staged))
                .isInstanceOfSatisfying(WikiPublishException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(WikiPublishException.Reason.FILESYSTEM_FAILURE));

        assertThat(target).doesNotExist();
        assertThat(staged.temporaryPath()).doesNotExist();
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void cleansStagedFileAndPreservesTargetThatAppearsBeforeCommit() throws Exception {
        WikiAtomicFilePublisher publisher = new WikiAtomicFilePublisher(new NoReplaceAtomicWikiFileCommitter(),
                new WikiPublishedMarkdownValidator());
        WikiDraft draft = draft();
        String content = new WikiPublishedMarkdownRenderer(new WikiDraftMarkdownRenderer())
                .render(draft, "wiki:target-race", 14L, 1, "2026-08-29T00:00:00Z");
        String hash = WikiContentHash.sha256(content);
        Path target = tempDir.resolve("target-race.md");
        StagedWikiFile staged = publisher.stage(target, content, hash, draft);
        String manualContent = "manual Obsidian content\n";
        Files.writeString(target, manualContent);

        assertThatThrownBy(() -> publisher.commit(staged))
                .isInstanceOfSatisfying(WikiPublishException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(WikiPublishException.Reason.TARGET_CONFLICT));

        assertThat(Files.readString(target)).isEqualTo(manualContent);
        assertThat(staged.temporaryPath()).doesNotExist();
    }

    private static WikiDraft draft() {
        List<Long> ids = List.of(7L);
        return new WikiDraft(11L, LlmProposalAction.CREATE, WikiPageType.CONCEPT, "Atomic Failure",
                WikiDraftTarget.createNew("vault/concepts/atomic-failure.md"),
                new WikiDraftFrontmatter("Atomic Failure", WikiPageType.CONCEPT, "Stable summary",
                        List.of("stable"), List.of(), List.of(3L), ids),
                List.of(new WikiDraftSection("Summary", "Stable content")),
                List.of(new WikiDraftWikilink("Target", "Related")),
                List.of(new WikiDraftEvidence(7L, 1, null, null, null, "Evidence excerpt")), ids,
                new WikiDraftContentContract("wiki-draft/v1", List.of("title"),
                        List.of("Summary"), true, true));
    }
}
