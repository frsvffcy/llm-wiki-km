package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.km.llmwiki.persistence.jooq.generated.Tables;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, bounded, read-only Vault Lint (#379). Scans the current published Wiki
 * of the active workspace through the same canonical authorities every other surface
 * uses — {@link PublishedWikiRepository} for durable metadata and
 * {@link PublishedWikiContentReader} for hash-validated content — and reports typed
 * findings: broken internal links, orphan pages, canonical content/schema validation
 * failures, and dangling provenance references.
 *
 * <p>Hard boundaries: no mutation of vault/archive/SQLite/Proposal/Draft or any derived
 * projection; no LLM or provider calls; no auto-fix. Derived projection drift (FTS /
 * Embedding / Graph) is deliberately NOT scanned here — projection health belongs to
 * its own contracts (#116 / #286) and must never be promoted to canonical truth.
 * Findings are sorted deterministically; the same canonical snapshot always yields an
 * identical report.
 */
@Service
public class VaultLintService {

    /** Bounded per-page read budget, aligned with the extraction absolute ceiling. */
    private static final int MAX_PAGE_BYTES = 1_000_000;
    private static final int MAX_DETAIL = 256;
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    private final WorkspaceService workspaceService;
    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader contentReader;
    private final KnowledgeProposalRepository proposalRepository;
    private final DSLContext dsl;

    public VaultLintService(WorkspaceService workspaceService,
                            PublishedWikiRepository publishedWikiRepository,
                            PublishedWikiContentReader contentReader,
                            KnowledgeProposalRepository proposalRepository,
                            DSLContext dsl) {
        this.workspaceService = workspaceService;
        this.publishedWikiRepository = publishedWikiRepository;
        this.contentReader = contentReader;
        this.proposalRepository = proposalRepository;
        this.dsl = dsl;
    }

    public VaultLintReport lintActiveWorkspace() {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        List<StoredPublishedWiki> pages =
                publishedWikiRepository.findAllPublished(workspace.id());

        Map<String, List<StoredPublishedWiki>> byKnowledgeId = pages.stream()
                .collect(Collectors.groupingBy(StoredPublishedWiki::knowledgeId));
        Map<String, List<StoredPublishedWiki>> byNormalizedTitle = pages.stream()
                .collect(Collectors.groupingBy(StoredPublishedWiki::normalizedTitle));

        Set<String> referencedTitles = new HashSet<>();
        List<VaultLintFinding> findings = new ArrayList<>();

        for (StoredPublishedWiki page : pages) {
            // Canonical identity integrity: duplicate stable identity is a structural
            // defect even though the durable schema also guards it (defense in depth).
            if (byKnowledgeId.get(page.knowledgeId()).size() > 1) {
                findings.add(finding(page, VaultLintFinding.Code.DUPLICATE_IDENTITY,
                        VaultLintFinding.Severity.ERROR, "duplicate knowledgeId in workspace"));
            }

            // Canonical content: the reader is the production authority — hash-validated
            // canonical bytes plus frontmatter agreement with durable metadata. Reusing it
            // here means the lint can never drift from the published-content contract.
            String body;
            try {
                body = contentReader.readSearchableContent(page, MAX_PAGE_BYTES);
            } catch (PublishedWikiValidationException invalid) {
                findings.add(finding(page, VaultLintFinding.Code.CANONICAL_CONTENT_INVALID,
                        VaultLintFinding.Severity.ERROR, bounded(invalid.getMessage())));
                continue;
            } catch (PublishedWikiUnavailableException unavailable) {
                findings.add(finding(page, VaultLintFinding.Code.CANONICAL_CONTENT_UNREADABLE,
                        VaultLintFinding.Severity.ERROR, bounded(unavailable.getMessage())));
                continue;
            }

            // Internal link integrity: every wikilink target must resolve to a published
            // page in the same workspace. No fuzzy-title guessing, no link rewriting.
            for (String target : extractWikilinkTargets(body)) {
                if (target.equals(page.normalizedTitle())) {
                    continue; // self-references are neither broken nor inbound references
                }
                if (!byNormalizedTitle.containsKey(target)) {
                    findings.add(finding(page, VaultLintFinding.Code.BROKEN_INTERNAL_LINK,
                            VaultLintFinding.Severity.ERROR,
                            "wikilink target not published: " + target));
                    continue;
                }
                referencedTitles.add(target);
            }
        }

        // Orphan knowledge: a published page with no inbound reference from another
        // published page. A health observation — never an error or an auto-deletion.
        for (StoredPublishedWiki page : pages) {
            if (!referencedTitles.contains(page.normalizedTitle())) {
                findings.add(finding(page, VaultLintFinding.Code.ORPHAN_PAGE,
                        VaultLintFinding.Severity.WARNING,
                        "no inbound reference from other published pages"));
            }
        }

        // Provenance/reference integrity: a page pointing at a proposal must find it.
        for (StoredPublishedWiki page : pages) {
            Long proposalId = pageProposalId(page);
            if (proposalId != null
                    && proposalRepository.findReviewableById(workspace.id(), proposalId).isEmpty()) {
                findings.add(finding(page, VaultLintFinding.Code.DANGLING_PROVENANCE,
                        VaultLintFinding.Severity.WARNING,
                        "referenced proposal is missing from the workspace"));
            }
        }

        findings.sort(VaultLintFinding::compareTo);
        return new VaultLintReport(workspace.id(), pages.size(), findings);
    }

    /** Wikilink targets ([[Page]] or [[Page|alias]]), normalized by the same authority. */
    static List<String> extractWikilinkTargets(String body) {
        List<String> targets = new ArrayList<>();
        for (String raw : WIKILINK.matcher(body == null ? "" : body).results()
                .map(matchResult -> matchResult.group(1))
                .map(group -> group.split("\\|", 2)[0].trim())
                .toList()) {
            if (!raw.isEmpty()) {
                targets.add(WikiTargetReference.normalizeTitle(raw));
            }
        }
        return targets;
    }

    private VaultLintFinding finding(StoredPublishedWiki page, VaultLintFinding.Code code,
                                     VaultLintFinding.Severity severity, String detail) {
        VaultLintFinding.Category category = switch (code) {
            case BROKEN_INTERNAL_LINK, ORPHAN_PAGE, DANGLING_PROVENANCE ->
                    VaultLintFinding.Category.REFERENCE;
            case CANONICAL_CONTENT_INVALID, CANONICAL_CONTENT_UNREADABLE, DUPLICATE_IDENTITY ->
                    VaultLintFinding.Category.CANONICAL_CONTENT;
        };
        return new VaultLintFinding(code, category, severity, page.knowledgeId(),
                page.markdownPath(), detail);
    }

    private Long pageProposalId(StoredPublishedWiki page) {
        Integer proposalId = dsl.select(Tables.KNOWLEDGE_PAGE.PROPOSAL_ID)
                .from(Tables.KNOWLEDGE_PAGE)
                .where(Tables.KNOWLEDGE_PAGE.ID.eq((int) page.id()))
                .fetchOne(0, Integer.class);
        return proposalId == null ? null : proposalId.longValue();
    }

    private String bounded(String detail) {
        String value = detail == null ? "" : detail.strip();
        return value.length() <= MAX_DETAIL ? value : value.substring(0, MAX_DETAIL);
    }
}
