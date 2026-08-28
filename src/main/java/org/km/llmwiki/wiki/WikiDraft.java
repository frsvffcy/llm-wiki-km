package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.HashSet;
import java.util.List;

/** Immutable, validated, render-ready output of an approved Knowledge Proposal. */
public record WikiDraft(long proposalId, LlmProposalAction action, WikiPageType pageType, String title,
                        WikiDraftTarget target, WikiDraftFrontmatter frontmatter,
                        List<WikiDraftSection> sections, List<WikiDraftWikilink> wikilinks,
                        List<WikiDraftEvidence> evidence, List<Long> sourceChunkIds,
                        WikiDraftContentContract expectedContentContract) {

    public WikiDraft {
        if (proposalId <= 0 || action == null || pageType == null || title == null || title.isBlank()
                || target == null || frontmatter == null || expectedContentContract == null) {
            throw new IllegalArgumentException("WikiDraft requires proposal, action, page type, title, and contracts");
        }
        sections = List.copyOf(sections);
        wikilinks = List.copyOf(wikilinks);
        evidence = List.copyOf(evidence);
        sourceChunkIds = List.copyOf(sourceChunkIds);
        if (sections.isEmpty() || evidence.isEmpty() || sourceChunkIds.isEmpty()) {
            throw new IllegalArgumentException("WikiDraft requires sections and evidence provenance");
        }
        if (new HashSet<>(sourceChunkIds).size() != sourceChunkIds.size()
                || sourceChunkIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("WikiDraft sourceChunkIds must be unique positive ids");
        }
        List<Long> evidenceIds = evidence.stream().map(WikiDraftEvidence::sourceChunkId).toList();
        if (!evidenceIds.equals(sourceChunkIds)) {
            throw new IllegalArgumentException("WikiDraft evidence must exactly match sourceChunkIds in order");
        }
        if (!frontmatter.title().equals(title) || frontmatter.pageType() != pageType
                || !frontmatter.sourceChunkIds().equals(sourceChunkIds)) {
            throw new IllegalArgumentException("WikiDraft frontmatter must agree with the draft contract");
        }
        List<String> headings = sections.stream().map(WikiDraftSection::heading).toList();
        if (!expectedContentContract.requiredSectionHeadings().equals(headings)) {
            throw new IllegalArgumentException("WikiDraft sections must satisfy expectedContentContract order");
        }
        if (action != LlmProposalAction.CREATE && action != LlmProposalAction.MERGE) {
            throw new IllegalArgumentException("WikiDraft action must be CREATE or MERGE");
        }
        if ((action == LlmProposalAction.CREATE && target.kind() != WikiDraftTarget.Kind.CREATE_NEW)
                || (action == LlmProposalAction.MERGE
                && target.kind() != WikiDraftTarget.Kind.EXISTING_REFERENCE)) {
            throw new IllegalArgumentException("WikiDraft target kind must agree with proposal action");
        }
    }
}
