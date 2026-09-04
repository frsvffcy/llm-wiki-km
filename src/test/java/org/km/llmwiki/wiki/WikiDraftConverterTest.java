package org.km.llmwiki.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.KnowledgeCandidateType;
import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class WikiDraftConverterTest {

    private final WikiDraftConverter converter = new WikiDraftConverter(
            new ObjectMapper(), new CandidatePageTypeResolver(),
            new WikiPathContract()::resolveLogicalPath);

    @Test
    void producesDeterministicNormalizedRenderReadyStructure() {
        WikiDraftConversionSource source = source(LlmProposalAction.CREATE, null, """
                {
                  "title": "  Spring  Boot  3  ",
                  "pageType": "TECHNOLOGY",
                  "summary": " Runtime   framework ",
                  "tags": ["Java", " framework ", "java"],
                  "aliases": ["Boot 3", " Spring Boot Three "],
                  "sourceChunkIds": [22, 11],
                  "sections": [
                    {"heading":" Overview ","content":"Line one.  \\r\\nLine two.   "},
                    {"heading":"Details","content":"Stable content."}
                  ],
                  "wikilinks": [
                    {"targetTitle":"Java","label":"Java"},
                    {"targetTitle":"Architecture","label":"architecture"},
                    {"targetTitle":"Java","label":"Java"}
                  ]
                }
                """);

        WikiDraft first = converter.convert(source);
        WikiDraft second = converter.convert(source);

        assertThat(first).isEqualTo(second);
        assertThat(first.pageType()).isEqualTo(WikiPageType.TECHNOLOGY);
        assertThat(first.title()).isEqualTo("Spring Boot 3");
        assertThat(first.target().logicalRelativePath()).isEqualTo("vault/technologies/spring-boot-3.md");
        assertThat(first.frontmatter().tags()).containsExactly("framework", "java");
        assertThat(first.sections()).containsExactly(
                new WikiDraftSection("Overview", "Line one.\nLine two."),
                new WikiDraftSection("Details", "Stable content."));
        assertThat(first.wikilinks()).containsExactly(
                new WikiDraftWikilink("Architecture", "architecture"),
                new WikiDraftWikilink("Java", "Java"));
        assertThat(first.sourceChunkIds()).containsExactly(11L, 22L);
        assertThat(first.evidence()).extracting(WikiDraftEvidence::sourceChunkId).containsExactly(11L, 22L);
        assertThat(first.expectedContentContract().requiredSectionHeadings())
                .containsExactly("Overview", "Details");
    }

    @Test
    void rejectsNonApprovedAndUnsupportedActions() {
        WikiDraftConversionSource review = new WikiDraftConversionSource(9, 1, 2, LlmProposalAction.CREATE,
                KnowledgeProposalStatus.REVIEW, null, "{}", KnowledgeCandidateType.CONCEPT, "Title", "Summary",
                List.of(11L), List.of(evidence(11, 1)));

        assertThatThrownBy(() -> converter.convert(review))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.PROPOSAL_NOT_APPROVED);
        assertThatThrownBy(() -> converter.convert(source(LlmProposalAction.LINK_ONLY, null, "{}")))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.UNSUPPORTED_ACTION);
    }

    @Test
    void rejectsEvidenceMismatchAndFilesystemLikeMergeReference() {
        WikiDraftConversionSource evidenceMismatch = source(LlmProposalAction.CREATE, null,
                "{\"sourceChunkIds\":[11,99]}");
        assertThatThrownBy(() -> converter.convert(evidenceMismatch))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.INVALID_EVIDENCE);

        WikiDraftConversionSource unsafeMerge = source(LlmProposalAction.MERGE, "../../outside.md", "{} ");
        assertThatThrownBy(() -> converter.convert(unsafeMerge))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.UNSAFE_TARGET_REFERENCE);
    }

    @Test
    void keepsMergeTargetSemanticAndUnresolvedForLaterResolution() {
        WikiDraftConversionSource merge = new WikiDraftConversionSource(9, 1, 2, LlmProposalAction.MERGE,
                KnowledgeProposalStatus.APPROVED, "wiki:deployment-runbook", """
                {
                  "pageType":"HOWTO",
                  "sections":[{"heading":"Steps","content":"Deploy deterministically."}]
                }
                """, KnowledgeCandidateType.PROCEDURE, "Deployment Runbook", "Deployment steps",
                List.of(11L), List.of(evidence(11, 1)));

        WikiDraft draft = converter.convert(merge);

        assertThat(draft.pageType()).isEqualTo(WikiPageType.HOWTO);
        assertThat(draft.target().kind()).isEqualTo(WikiDraftTarget.Kind.EXISTING_REFERENCE);
        assertThat(draft.target().reference()).isEqualTo("wiki:deployment-runbook");
        assertThat(draft.target().logicalRelativePath()).isNull();
    }

    private static WikiDraftConversionSource source(LlmProposalAction action, String target, String data) {
        return new WikiDraftConversionSource(9, 1, 2, action, KnowledgeProposalStatus.APPROVED, target, data,
                KnowledgeCandidateType.CONCEPT, "Candidate Title", "Candidate summary",
                List.of(11L), List.of(evidence(22, 2), evidence(11, 1)));
    }

    private static KnowledgeProposalEvidence evidence(long id, int chunkNo) {
        return new KnowledgeProposalEvidence(id, chunkNo, null, null, null, " Evidence " + id + " ");
    }
}
