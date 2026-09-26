package org.km.llmwiki.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchEligibilityPolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared ASK evidence currentness authority (#649).
 *
 * <p>Single revalidation boundary used by ingress-dedup, approve, draft
 * creation and publish: every citation in the durable
 * {@code ASK_CITATIONS_JSON} snapshot must still resolve to current
 * workspace-scoped authority right now. SOURCE chunks must exist in the
 * current workspace with a canonical document status; WIKI citations must
 * still be published/current with revision equal to the persisted validated
 * revision. Legacy rows without a persisted revision, malformed snapshots and
 * cross-workspace references all fail closed — the validator never invents a
 * revision and never treats provider/model metadata as authority.
 */
@Service
public class AskProposalEvidenceCurrentnessValidator {

    private final DSLContext dsl;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;
    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader publishedWikiContentReader;
    private final ObjectMapper objectMapper;

    public AskProposalEvidenceCurrentnessValidator(DSLContext dsl,
                                                   SourceSearchAuthorityRepository sourceAuthorityRepository,
                                                   PublishedWikiRepository publishedWikiRepository,
                                                   PublishedWikiContentReader publishedWikiContentReader,
                                                   ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
        this.publishedWikiRepository = publishedWikiRepository;
        this.publishedWikiContentReader = publishedWikiContentReader;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns invalid identities for one persisted snapshot string. Empty means
     * current. Legacy snapshots (missing wikiRevision) and malformed snapshots
     * return a deterministic fail-closed identity instead of throwing a second
     * exception type, so every caller can map to its own typed contract.
     */
    public List<String> findInvalid(long workspaceId, String citationsJson) {
        AskProposalEvidenceSnapshot snapshot;
        try {
            snapshot = AskProposalEvidenceSnapshot.parse(citationsJson, objectMapper);
        } catch (AskCitationInvalidException malformed) {
            return List.copyOf(malformed.invalidIdentities());
        }
        if (snapshot.legacy()) {
            return List.of("LEGACY_ASK_CITATION");
        }
        List<String> invalid = new ArrayList<>();
        boolean hasSource = false;
        for (AskProposalEvidenceSnapshot.AskEvidenceCitation citation : snapshot.citations()) {
            if ("SOURCE".equals(citation.kind())) {
                hasSource = true;
                Long chunkId = citation.sourceChunkId();
                if (chunkId == null || chunkId <= 0 || chunkId > Integer.MAX_VALUE
                        || !sourceChunkIsCurrent(workspaceId, chunkId)) {
                    invalid.add("SOURCE_CHUNK:" + chunkId);
                }
            } else if ("WIKI".equals(citation.kind())) {
                String path = citation.wikiPath();
                Integer revision = citation.wikiRevision();
                // New-format snapshots always carry a validated revision; a
                // missing revision here is a persistence bug, not legacy.
                if (path == null || path.isBlank() || revision == null || revision < 1) {
                    invalid.add("WIKI:" + path);
                    continue;
                }
                var page = findCurrentWiki(workspaceId, path, revision);
                if (page.isEmpty()) {
                    invalid.add("WIKI:" + path + "@r" + revision);
                    continue;
                }
                String knowledgeId = citation.knowledgeId();
                if (knowledgeId != null && !knowledgeId.equals(page.get().knowledgeId())) {
                    invalid.add("WIKI:" + path + "@r" + revision);
                }
            } else {
                invalid.add("UNKNOWN_KIND:" + citation.kind());
            }
        }
        if (!hasSource) {
            invalid.add("no SOURCE_CHUNK citation: proposal evidence requires at least one");
        }
        return List.copyOf(invalid);
    }

    /**
     * Validates the persisted snapshot for one workspace-scoped proposal.
     * Non-ASK proposals are a no-op (empty list); ASK proposals return invalid
     * identities or empty when current. Unknown proposals in this workspace
     * return empty so the caller's existing visibility boundary still owns the
     * typed 404 (this validator never masks NOT_FOUND as evidence failure).
     */
    public List<String> validatePersisted(long workspaceId, long proposalId) {
        Optional<AskEvidenceRow> row = loadAskRow(workspaceId, proposalId);
        if (row.isEmpty()) {
            return List.of();
        }
        if (!"ASK".equals(row.get().sourceKind())) {
            return List.of();
        }
        return findInvalid(workspaceId, row.get().citationsJson());
    }

    /** Throws {@link AskCitationInvalidException} when persisted evidence is stale. */
    public void requireCurrent(long workspaceId, long proposalId) {
        List<String> invalid = validatePersisted(workspaceId, proposalId);
        if (!invalid.isEmpty()) {
            throw new AskCitationInvalidException(invalid);
        }
    }

    private Optional<AskEvidenceRow> loadAskRow(long workspaceId, long proposalId) {
        var table = org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL;
        return dsl.select(table.SOURCE_KIND, table.ASK_CITATIONS_JSON)
                .from(table)
                .where(table.ID.eq((int) proposalId))
                .and(table.WORKSPACE_ID.eq((int) workspaceId))
                .fetchOptional(r -> new AskEvidenceRow(
                        r.get(table.SOURCE_KIND), r.get(table.ASK_CITATIONS_JSON)));
    }

    public Optional<StoredPublishedWiki> findCurrentWiki(long workspaceId, String path,
                                                         Integer expectedRevision) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
            return Optional.empty();
        }
        Optional<StoredPublishedWiki> page =
                publishedWikiRepository.findPublishedByMarkdownPath(workspaceId, path);
        if (page.isEmpty() || expectedRevision == null || expectedRevision < 1
                || page.get().revision() != expectedRevision) {
            return Optional.empty();
        }
        try {
            publishedWikiContentReader.readSearchableContent(page.get());
        } catch (PublishedWikiValidationException staleCanonicalContent) {
            return Optional.empty();
        }
        return page;
    }

    public boolean sourceChunkIsCurrent(long workspaceId, Long chunkId) {
        if (chunkId == null || chunkId <= 0 || chunkId > Integer.MAX_VALUE) {
            return false;
        }
        var document = sourceAuthorityRepository.findDocumentByChunk(workspaceId, chunkId);
        if (document.isEmpty() || !SourceSearchEligibilityPolicy.documentEligible(document.get())) {
            return false;
        }
        return document.get().chunks().stream()
                .filter(chunk -> chunk.sourceChunkId() == chunkId)
                .anyMatch(SourceSearchEligibilityPolicy::chunkEligible);
    }

    private record AskEvidenceRow(String sourceKind, String citationsJson) {
    }
}
