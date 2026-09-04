package org.km.llmwiki.search;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** Resolves query-matched Source documents whose projection is provably fresh. */
@Component
public class SearchServingConsistencyGate {

    private final FtsSearchIndexRepository ftsRepository;
    private final SourceSearchAuthorityRepository authorityRepository;
    private final SourceSearchIndexSyncRepository syncRepository;

    public SearchServingConsistencyGate(FtsSearchIndexRepository ftsRepository,
                                        SourceSearchAuthorityRepository authorityRepository,
                                        SourceSearchIndexSyncRepository syncRepository) {
        this.ftsRepository = ftsRepository;
        this.authorityRepository = authorityRepository;
        this.syncRepository = syncRepository;
    }

    public Set<Long> freshSourceDocumentIds(long workspaceId, String query, Long documentId) {
        Set<Long> fresh = new LinkedHashSet<>();
        for (Long matchedDocumentId
                : ftsRepository.findMatchedSourceDocumentIds(workspaceId, query, documentId)) {
            var authority = authorityRepository.findDocument(workspaceId, matchedDocumentId);
            var ledger = syncRepository.find(workspaceId, matchedDocumentId);
            if (authority.isEmpty() || ledger.isEmpty()
                    || !SourceSearchEligibilityPolicy.documentEligible(authority.get())
                    || ledger.get().status() != SourceSearchIndexSyncStatus.SYNCED
                    || !CjkBigramProjector.VERSION.equals(ledger.get().projectionVersion())) {
                continue;
            }
            var eligible = SourceSearchFreshness.eligibleDocuments(authority.get());
            String fingerprint = SourceSearchFreshness.fingerprint(eligible);
            if (!eligible.isEmpty()
                    && ledger.get().eligibleChunkCount() == eligible.size()
                    && ledger.get().indexedChunkCount() == eligible.size()
                    && fingerprint.equals(ledger.get().canonicalFingerprint())
                    && fingerprint.equals(ledger.get().indexedFingerprint())) {
                fresh.add(matchedDocumentId);
            }
        }
        return Set.copyOf(fresh);
    }
}
