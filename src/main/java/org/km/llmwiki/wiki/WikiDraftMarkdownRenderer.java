package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/** Deterministic Java renderer from the validated #89 structured Draft contract. */
@Component
public class WikiDraftMarkdownRenderer {

    public String render(WikiDraft draft) {
        StringBuilder markdown = new StringBuilder();
        WikiDraftFrontmatter frontmatter = draft.frontmatter();
        markdown.append("---\n");
        scalar(markdown, "title", frontmatter.title());
        scalar(markdown, "pageType", frontmatter.pageType().name());
        scalar(markdown, "summary", frontmatter.summary());
        list(markdown, "tags", frontmatter.tags(), WikiDraftMarkdownRenderer::quote);
        list(markdown, "aliases", frontmatter.aliases(), WikiDraftMarkdownRenderer::quote);
        list(markdown, "sourceDocumentIds", frontmatter.sourceDocumentIds(), String::valueOf);
        list(markdown, "sourceChunkIds", frontmatter.sourceChunkIds(), String::valueOf);
        markdown.append("---\n\n# ").append(draft.title()).append("\n\n");

        for (WikiDraftSection section : draft.sections()) {
            markdown.append("## ").append(section.heading()).append("\n\n")
                    .append(section.content().strip()).append("\n\n");
        }
        if (!draft.wikilinks().isEmpty()) {
            markdown.append("## Related Links\n\n");
            for (WikiDraftWikilink link : draft.wikilinks()) {
                markdown.append("- [[").append(link.targetTitle()).append('|').append(link.label()).append("]]\n");
            }
            markdown.append('\n');
        }
        markdown.append("## Evidence\n\n");
        for (WikiDraftEvidence evidence : draft.evidence()) {
            markdown.append("- sourceChunkId: ").append(evidence.sourceChunkId())
                    .append(", chunkNo: ").append(evidence.chunkNo());
            if (evidence.pageNo() != null) {
                markdown.append(", pageNo: ").append(evidence.pageNo());
            }
            markdown.append('\n');
            for (String line : evidence.excerpt().strip().split("\\R", -1)) {
                markdown.append("  > ").append(line).append('\n');
            }
        }
        return markdown.toString();
    }

    private static void scalar(StringBuilder markdown, String name, String value) {
        markdown.append(name).append(": ").append(quote(value)).append('\n');
    }

    private static <T> void list(StringBuilder markdown, String name, List<T> values,
                                 Function<T, String> formatter) {
        if (values.isEmpty()) {
            markdown.append(name).append(": []\n");
            return;
        }
        markdown.append(name).append(":\n");
        values.forEach(value -> markdown.append("  - ").append(formatter.apply(value)).append('\n'));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + '"';
    }
}
