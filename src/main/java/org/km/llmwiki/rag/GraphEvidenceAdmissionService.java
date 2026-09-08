package org.km.llmwiki.rag;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.graph.CanonicalGraphRelationProfile;
import org.km.llmwiki.graph.GraphAuthorityKind;
import org.km.llmwiki.graph.GraphAuthorityReference;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphFreshness;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionReadinessReader;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphRelation;
import org.km.llmwiki.graph.GraphSnapshotCurrentness;
import org.km.llmwiki.graph.GraphTraversalCandidate;
import org.km.llmwiki.graph.GraphTraversalResult;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchEligibilityPolicy;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.PublishedWikiUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiValidationException;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Application boundary that converts a returned {@link GraphTraversalResult} into canonical,
 * revalidated evidence items. Graph topology is derived navigation data: projection rows, paths,
 * and vendor identifiers never become citation authority. A candidate may only enter the evidence
 * set when the projection still proves exactly the traversal snapshot at admission time and its
 * canonical authority still satisfies the workspace, identity, provenance, freshness, and
 * eligibility contract. Batch-level drift fails closed; the lexical and vector baselines are
 * never affected by graph admission failures.
 */
@Service
public class GraphEvidenceAdmissionService {

    private static final String DOCUMENT_KEY_PREFIX = "document:";
    private static final String CHUNK_KEY_SEGMENT = ":chunk:";

    private final GraphProjectionReadinessReader readinessReader;
    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader publishedWikiContentReader;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;

    public GraphEvidenceAdmissionService(GraphProjectionReadinessReader readinessReader,
                                         PublishedWikiRepository publishedWikiRepository,
                                         PublishedWikiContentReader publishedWikiContentReader,
                                         SourceSearchAuthorityRepository sourceAuthorityRepository) {
        if (readinessReader == null || publishedWikiRepository == null
                || publishedWikiContentReader == null || sourceAuthorityRepository == null) {
            throw new IllegalArgumentException("Graph evidence admission dependencies are required");
        }
        this.readinessReader = readinessReader;
        this.publishedWikiRepository = publishedWikiRepository;
        this.publishedWikiContentReader = publishedWikiContentReader;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
    }

    public GraphEvidenceAdmissionResult admit(GraphEvidenceAdmissionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Graph evidence admission request is required");
        }
        EvidenceWorkspace workspace = request.workspace();
        GraphTraversalResult traversal = request.traversal();
        GraphProjectionSnapshot expected = request.expectedSnapshot();
        GraphWorkspaceScope scope = new GraphWorkspaceScope(workspace.id());
        if (!scope.equals(expected.workspace()) || !scope.equals(traversal.snapshot().workspace())) {
            throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
        }

        // First consumption-window check: a traversal result that was current when returned may
        // already be stale by the time it is converted into evidence.
        GraphSnapshotCurrentness.requireCurrent(
                readinessReader.readiness(scope), expected, false);

        GraphEvidenceAdmissionBudget budget = request.budget();
        List<EvidenceItem> evidence = new ArrayList<>();
        List<GraphCandidateRejection> rejections = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments = new HashMap<>();
        int usedCharacters = 0;
        boolean truncated = false;

        for (GraphTraversalCandidate candidate : traversal.candidates()) {
            if (evidence.size() >= budget.maxItems()
                    || usedCharacters >= budget.maxCharacters()) {
                truncated = true;
                break;
            }
            RevalidatedCandidate revalidated =
                    revalidate(candidate, workspace, expected, sourceDocuments);
            if (revalidated.rejection() != null) {
                rejections.add(revalidated.rejection());
                continue;
            }
            String identity = revalidated.evidence().kind().name() + ":"
                    + revalidated.evidence().stableId();
            if (!identities.add(identity)) {
                rejections.add(new GraphCandidateRejection(candidate.entity().identity(),
                        GraphEvidenceRejectionReason.DUPLICATE_IDENTITY, identity));
                continue;
            }
            int remaining = budget.maxCharacters() - usedCharacters;
            BoundedText bounded = bound(revalidated.evidence().content(), remaining);
            if (bounded.text().isBlank()) {
                rejections.add(rejection(candidate.entity().identity(),
                        GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                        "canonical content is blank"));
                continue;
            }
            evidence.add(revalidated.evidence().toItem(workspace, score(candidate.depth()),
                    bounded.text(), bounded.truncated()));
            usedCharacters += bounded.characters();
            truncated |= bounded.truncated();
            if (bounded.truncated()) {
                truncated = true;
                break;
            }
        }

        // Second consumption-window check: discard everything if any drift occurred while
        // canonical authorities were being read. Stale graph results never substitute evidence.
        GraphSnapshotCurrentness.requireCurrent(
                readinessReader.readiness(scope), expected, true);

        EvidenceBudget used = new EvidenceBudget(budget.maxItems(), budget.maxCharacters(),
                evidence.size(), usedCharacters, (usedCharacters + 3) / 4, truncated);
        return new GraphEvidenceAdmissionResult(expected, workspace, evidence, used,
                traversal.candidates().size(), rejections.size(), rejections);
    }

    private RevalidatedCandidate revalidate(GraphTraversalCandidate candidate,
                                            EvidenceWorkspace workspace,
                                            GraphProjectionSnapshot expected,
                                            Map<Long, Optional<SourceSearchAuthorityDocument>>
                                                    sourceDocuments) {
        GraphEntity entity = candidate.entity();
        GraphEntityIdentity identity = entity.identity();
        GraphWorkspaceScope scope = new GraphWorkspaceScope(workspace.id());
        if (!scope.equals(identity.workspace())
                || !scope.equals(entity.provenance().authority().workspace())) {
            return rejected(identity, GraphEvidenceRejectionReason.WORKSPACE_MISMATCH, null);
        }
        if (!entity.provenance().eligible()) {
            return rejected(identity, GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                    "provenance eligibility is not ELIGIBLE");
        }
        if (!expected.projectionVersion().equals(entity.projectionVersion())) {
            return rejected(identity, GraphEvidenceRejectionReason.STALE_PROVENANCE,
                    "entity projection version does not match the traversal snapshot");
        }
        for (GraphRelation relation : candidate.path()) {
            if (!CanonicalGraphRelationProfile.admits(relation.type())) {
                return rejected(identity, GraphEvidenceRejectionReason.DISALLOWED_RELATION,
                        relation.type().name());
            }
            if (!expected.projectionVersion().equals(relation.projectionVersion())
                    || !scope.equals(relation.provenance().authority().workspace())) {
                return rejected(identity, GraphEvidenceRejectionReason.STALE_PROVENANCE,
                        relation.type().name());
            }
        }
        if (identity.type().authorityKind() != null
                && !identity.canonicalKey().equals(identity.type().authorityKind().wireValue()
                        + ":" + entity.provenance().authority().stableId())) {
            return rejected(identity, GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    "canonical key does not match authority reference");
        }
        return switch (identity.type()) {
            case WIKI_PAGE -> admitWiki(entity, workspace);
            case SOURCE_CHUNK -> admitSourceChunk(entity, workspace, sourceDocuments);
            case SOURCE_DOCUMENT -> admitSourceDocument(entity, workspace, sourceDocuments);
            case TAG, CONCEPT -> rejected(identity,
                    GraphEvidenceRejectionReason.NON_EVIDENCE_ENTITY,
                    identity.type().name() + " is navigation-only");
        };
    }

    private RevalidatedCandidate admitWiki(GraphEntity entity, EvidenceWorkspace workspace) {
        GraphAuthorityReference authority = entity.provenance().authority();
        if (authority.kind() != GraphAuthorityKind.WIKI_PAGE) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    "wiki entity does not reference wiki authority");
        }
        GraphFreshness freshness = entity.provenance().freshness();
        if (freshness.revision() == null || freshness.contentHash() == null) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    "wiki provenance freshness is incomplete");
        }
        Optional<StoredPublishedWiki> stored;
        try {
            stored = publishedWikiRepository.findPublishedByKnowledgeId(
                    workspace.id(), authority.stableId());
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WIKI_AUTHORITY,
                    infrastructureFailure);
        }
        if (stored.isEmpty()) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_MISSING,
                    authority.stableId());
        }
        StoredPublishedWiki page = stored.get();
        if (!freshness.revision().equals(page.revision())
                || !freshness.contentHash().equals(page.contentHash())) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_STALE,
                    authority.stableId());
        }
        String content;
        try {
            content = publishedWikiContentReader.readSearchableContent(page);
        } catch (PublishedWikiValidationException expectedDrift) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_STALE,
                    authority.stableId());
        } catch (PublishedWikiUnavailableException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WIKI_AUTHORITY,
                    infrastructureFailure);
        }
        if (content.isBlank()) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                    authority.stableId());
        }
        return new RevalidatedCandidate(new AdmittedEvidence(
                EvidenceKind.WIKI, page.knowledgeId(), content, page.contentHash(),
                page.knowledgeId(), page.title(), page.pageType().name(), page.markdownPath(),
                page.revision(), null, null, null, null, null, null, null), null);
    }

    private RevalidatedCandidate admitSourceChunk(GraphEntity entity, EvidenceWorkspace workspace,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        GraphAuthorityReference authority = entity.provenance().authority();
        if (authority.kind() != GraphAuthorityKind.SOURCE_CHUNK) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    "chunk entity does not reference chunk authority");
        }
        DocumentChunkKey key = parseDocumentChunkKey(authority.stableId());
        if (key == null || !chunkNumberMatches(entity, key.chunkNo())) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    authority.stableId());
        }
        GraphFreshness freshness = entity.provenance().freshness();
        if (freshness.contentHash() == null) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    "chunk provenance freshness is incomplete");
        }
        Optional<SourceSearchAuthorityDocument> document =
                findSourceDocument(entity.identity(), workspace, key.documentId(), sourceDocuments);
        if (document.isEmpty()) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_MISSING,
                    authority.stableId());
        }
        SourceSearchAuthorityDocument doc = document.get();
        if (!SourceSearchEligibilityPolicy.documentEligible(doc)) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                    doc.documentName());
        }
        SourceSearchAuthorityChunk chunk = doc.chunks().stream()
                .filter(candidate -> candidate.chunkNo() == key.chunkNo())
                .findFirst()
                .orElse(null);
        if (chunk == null) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_MISSING,
                    authority.stableId());
        }
        if (!SourceSearchEligibilityPolicy.chunkEligible(chunk)) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                    doc.documentName() + "#" + key.chunkNo());
        }
        if (!freshness.contentHash().equals(chunk.contentHash())) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_STALE,
                    authority.stableId());
        }
        return new RevalidatedCandidate(new AdmittedEvidence(
                EvidenceKind.SOURCE_CHUNK, Long.toString(chunk.sourceChunkId()),
                chunk.normalizedContent(), chunk.contentHash(), null, null, null, null, null,
                chunk.sourceChunkId(), doc.documentId(), doc.documentName(), chunk.chunkNo(),
                chunk.pageNo(), chunk.section(), chunk.headingPath()), null);
    }

    private RevalidatedCandidate admitSourceDocument(GraphEntity entity,
                                                     EvidenceWorkspace workspace,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        GraphAuthorityReference authority = entity.provenance().authority();
        if (authority.kind() != GraphAuthorityKind.SOURCE_DOCUMENT) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    "document entity does not reference document authority");
        }
        Long documentId = parseDocumentId(authority.stableId());
        if (documentId == null) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    authority.stableId());
        }
        GraphFreshness freshness = entity.provenance().freshness();
        if (freshness.contentHash() == null) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.MALFORMED_PROVENANCE,
                    "document provenance freshness is incomplete");
        }
        Optional<SourceSearchAuthorityDocument> document =
                findSourceDocument(entity.identity(), workspace, documentId, sourceDocuments);
        if (document.isEmpty()) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_MISSING,
                    authority.stableId());
        }
        SourceSearchAuthorityDocument doc = document.get();
        if (!SourceSearchEligibilityPolicy.documentEligible(doc)) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_INELIGIBLE,
                    doc.documentName());
        }
        if (!freshness.contentHash().equals(doc.documentSha256())) {
            return rejected(entity.identity(), GraphEvidenceRejectionReason.AUTHORITY_STALE,
                    authority.stableId());
        }
        // The document authority is current, but a document is not a chunk-level citation
        // authority; citation identity requires resolution to an eligible source chunk.
        return rejected(entity.identity(),
                GraphEvidenceRejectionReason.NON_CITATION_AUTHORITY,
                "document:" + documentId);
    }

    private Optional<SourceSearchAuthorityDocument> findSourceDocument(
            GraphEntityIdentity identity, EvidenceWorkspace workspace, long documentId,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        try {
            return sourceDocuments.computeIfAbsent(documentId,
                    key -> sourceAuthorityRepository.findDocument(workspace.id(), key));
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.SOURCE_AUTHORITY,
                    infrastructureFailure);
        }
    }

    private static boolean chunkNumberMatches(GraphEntity entity, int chunkNo) {
        String metadataChunkNo = entity.metadata().entries().get("chunk_no");
        return metadataChunkNo != null && metadataChunkNo.equals(Integer.toString(chunkNo));
    }

    private static DocumentChunkKey parseDocumentChunkKey(String stableId) {
        if (!stableId.startsWith(DOCUMENT_KEY_PREFIX)) {
            return null;
        }
        int separator = stableId.indexOf(CHUNK_KEY_SEGMENT, DOCUMENT_KEY_PREFIX.length());
        if (separator < 0) {
            return null;
        }
        Long documentId = parseLong(stableId.substring(DOCUMENT_KEY_PREFIX.length(), separator));
        Integer chunkNo = parseInteger(stableId.substring(separator + CHUNK_KEY_SEGMENT.length()));
        return documentId == null || chunkNo == null || chunkNo < 1
                ? null
                : new DocumentChunkKey(documentId, chunkNo);
    }

    private static Long parseDocumentId(String stableId) {
        if (!stableId.startsWith(DOCUMENT_KEY_PREFIX)) {
            return null;
        }
        Long documentId = parseLong(stableId.substring(DOCUMENT_KEY_PREFIX.length()));
        return documentId == null || documentId < 1 ? null : documentId;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank() || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException oversizedKey) {
            return null;
        }
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank() || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException oversizedKey) {
            return null;
        }
    }

    private static double score(int depth) {
        // Application-owned deterministic ordering material; never a vendor score.
        return 1.0d / Math.max(1, depth);
    }

    private static GraphCandidateRejection rejection(GraphEntityIdentity entity,
                                                     GraphEvidenceRejectionReason reason,
                                                     String detail) {
        return new GraphCandidateRejection(entity, reason, detail);
    }

    private static RevalidatedCandidate rejected(GraphEntityIdentity entity,
                                                 GraphEvidenceRejectionReason reason,
                                                 String detail) {
        return new RevalidatedCandidate(null, rejection(entity, reason, detail));
    }

    private static BoundedText bound(String content, int maxCharacters) {
        int count = content.codePointCount(0, content.length());
        if (count <= maxCharacters) {
            return new BoundedText(content, count, false);
        }
        int end = content.offsetByCodePoints(0, maxCharacters);
        return new BoundedText(content.substring(0, end), maxCharacters, true);
    }

    private record DocumentChunkKey(long documentId, int chunkNo) {
    }

    private record BoundedText(String text, int characters, boolean truncated) {
    }

    /** Canonical authority fields re-validated at admission time; identity is application-owned. */
    private record AdmittedEvidence(EvidenceKind kind, String stableId, String content,
                                    String contentHash, String knowledgeId, String title,
                                    String pageType, String path, Integer revision,
                                    Long sourceChunkId, Long documentId, String documentName,
                                    Integer chunkNo, Integer pageNo, String section,
                                    String headingPath) {

        EvidenceItem toItem(EvidenceWorkspace workspace, double score, String boundedContent,
                            boolean truncated) {
            return new EvidenceItem(kind, stableId, workspace, score, boundedContent, null,
                    truncated, contentHash, knowledgeId, title, pageType, path, revision,
                    sourceChunkId, documentId, documentName, chunkNo, pageNo, section,
                    headingPath);
        }
    }

    private record RevalidatedCandidate(AdmittedEvidence evidence,
                                        GraphCandidateRejection rejection) {
    }
}
