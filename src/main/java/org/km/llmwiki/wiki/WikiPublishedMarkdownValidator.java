package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fail-closed validation of the exact bytes that will become the durable Vault page. */
@Component
public class WikiPublishedMarkdownValidator {

    private static final List<String> REQUIRED_FRONTMATTER = List.of(
            "id:", "title:", "type:", "status:", "aliases:", "tags:", "sources:",
            "created_at:", "updated_at:", "proposal_id:", "draft_id:", "revision:");

    public void validate(byte[] bytes, String expectedContent, String expectedHash, WikiDraft draft) {
        String actual = new String(bytes, StandardCharsets.UTF_8);
        if (!actual.equals(expectedContent) || !WikiContentHash.sha256(bytes).equals(expectedHash)) {
            throw new WikiPublishException(WikiPublishException.Reason.CONTENT_VALIDATION_FAILED,
                    "Staged Wiki Markdown bytes or content hash do not match the controlled renderer output");
        }
        if (!actual.startsWith("---\n") || actual.indexOf("\n---\n\n") < 0) {
            throw invalid("Published Wiki Markdown requires a complete YAML frontmatter block");
        }
        for (String field : REQUIRED_FRONTMATTER) {
            if (actual.lines().noneMatch(line -> line.startsWith(field))) {
                throw invalid("Published Wiki Markdown is missing required frontmatter field " + field);
            }
        }
        if (!actual.contains("\n# " + draft.title() + "\n")) {
            throw invalid("Published Wiki Markdown title does not match the READY Draft");
        }
        for (String heading : draft.expectedContentContract().requiredSectionHeadings()) {
            if (!actual.contains("\n## " + heading + "\n")) {
                throw invalid("Published Wiki Markdown is missing required section " + heading);
            }
        }
        if (draft.expectedContentContract().evidenceRequired() && !actual.contains("\n## Evidence\n")) {
            throw invalid("Published Wiki Markdown is missing required evidence provenance");
        }
    }

    private static WikiPublishException invalid(String message) {
        return new WikiPublishException(WikiPublishException.Reason.CONTENT_VALIDATION_FAILED, message);
    }
}
