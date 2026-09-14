package org.km.llmwiki.ai.query;

import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.ai.answer.ProviderUsageStatus;
import org.km.llmwiki.rag.EvidenceBudget;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;
import org.km.llmwiki.rag.RetrievalRequest;
import org.km.llmwiki.rag.RetrievalService;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.search.CjkBigramProjector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/** Executes at most one provider rewrite and one additional retrieval input. */
@Service
public final class QueryTransformationService {
    private static final int MAX_CODE_POINTS = 256;
    private static final int MAX_PROJECTED_TERMS = 64;
    private static final Pattern TECHNICAL_TOKEN = Pattern.compile(
            "(?iu)(?:[A-Z]{2,}-\\d{3,}|[A-Z][A-Za-z0-9_$]*(?:Error|Exception)|"
                    + "[a-z][a-z0-9_]*_[a-z0-9_]+|[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)");

    private final QueryTransformationPolicy policy;
    private final QueryRewriteClient rewriteClient;

    @Autowired
    public QueryTransformationService(QueryTransformationPolicyRegistry registry,
                                      QueryRewriteClient rewriteClient) {
        this(registry.active(), rewriteClient);
    }

    QueryTransformationService(QueryTransformationPolicy policy, QueryRewriteClient rewriteClient) {
        this.policy = policy;
        this.rewriteClient = rewriteClient;
    }

    public static QueryTransformationService disabled() {
        return new QueryTransformationService(new DisabledQueryTransformationPolicy(),
                new DisabledQueryRewriteClient());
    }

    public QueryTransformationResult apply(RetrievalRequest originalRequest,
                                           EvidenceBundle originalEvidence,
                                           RetrievalService retrievalService) {
        return apply(originalRequest, originalEvidence, retrievalService::retrieve);
    }

    /** Shared execution seam used by the read-only Inspector to observe rewrite retrieval. */
    public QueryTransformationResult apply(RetrievalRequest originalRequest,
                                           EvidenceBundle originalEvidence,
                                           Function<RetrievalRequest, EvidenceBundle> retrieval) {
        if (!policy.enabled()) {
            return original(originalEvidence, QueryTransformationStatus.NO_OP_POLICY_DISABLED,
                    QueryTransformationApplicability.POLICY_DISABLED,
                    ProviderUsageStatus.NOT_ATTEMPTED, null, null);
        }
        QueryTransformationApplicability applicability =
                policy.applicability(originalRequest.query(), originalEvidence);
        if (!applicability.applicable()) {
            return original(originalEvidence, QueryTransformationStatus.NO_OP_NOT_APPLICABLE,
                    applicability, ProviderUsageStatus.NOT_ATTEMPTED, null, null);
        }

        List<String> protectedTokens = protectedTokens(originalRequest.query());
        long started = System.nanoTime();
        QueryRewriteResult providerResult;
        try {
            providerResult = rewriteClient.rewriteWithMetadata(originalRequest.query(), protectedTokens);
        } catch (QueryRewriteException failure) {
            QueryTransformationStatus status = failure.type() == QueryRewriteException.Type.UNAVAILABLE
                    ? QueryTransformationStatus.FALLBACK_PROVIDER_UNAVAILABLE
                    : QueryTransformationStatus.FALLBACK_PROVIDER_INVALID;
            return original(originalEvidence, status, applicability, ProviderUsageStatus.UNAVAILABLE,
                    elapsedMillis(started), null);
        } catch (RuntimeException failure) {
            return original(originalEvidence, QueryTransformationStatus.FALLBACK_PROVIDER_UNAVAILABLE,
                    applicability, ProviderUsageStatus.UNAVAILABLE, elapsedMillis(started), null);
        }

        long latency = elapsedMillis(started);
        AnswerUsageMetadata usage = providerResult == null
                ? null : providerResult.usage().orElse(null);
        ProviderUsageStatus usageStatus = usage == null
                ? ProviderUsageStatus.UNAVAILABLE : ProviderUsageStatus.AVAILABLE;
        String candidate = providerResult == null ? null : providerResult.rewrittenQuery();
        String rewritten = candidate == null ? null
                : Normalizer.normalize(candidate.strip(), Normalizer.Form.NFC);
        QueryTransformationStatus invalid = validate(originalRequest.query(), rewritten,
                protectedTokens);
        if (invalid != null) {
            return original(originalEvidence, invalid, applicability, usageStatus, latency, usage);
        }

        RetrievalRequest rewriteRequest = RetrievalRequest.of(rewritten, originalRequest.mode(),
                originalRequest.strategy(), originalRequest.maxItems(), originalRequest.maxCharacters());
        try {
            EvidenceBundle rewrittenEvidence = retrieval.apply(rewriteRequest);
            EvidenceBundle merged = merge(originalEvidence, rewrittenEvidence);
            if (merged == null) {
                return original(originalEvidence,
                        QueryTransformationStatus.FALLBACK_RETRIEVAL_INVALID,
                        applicability, usageStatus, latency, usage);
            }
            return new QueryTransformationResult(merged,
                    execution(QueryTransformationStatus.REWRITE_APPLIED,
                            applicability, usageStatus, 2, latency, usage));
        } catch (RetrievalUnavailableException failure) {
            return new QueryTransformationResult(originalEvidence,
                    execution(QueryTransformationStatus.FALLBACK_RETRIEVAL_UNAVAILABLE,
                            applicability, usageStatus, 2, latency, usage));
        }
    }

    private QueryTransformationResult original(EvidenceBundle evidence,
                                               QueryTransformationStatus status,
                                               QueryTransformationApplicability applicability,
                                               ProviderUsageStatus usageStatus, Long latency,
                                               AnswerUsageMetadata usage) {
        return new QueryTransformationResult(evidence,
                execution(status, applicability, usageStatus, 1, latency, usage));
    }

    private QueryTransformationExecution execution(QueryTransformationStatus status,
                                                   QueryTransformationApplicability applicability,
                                                   ProviderUsageStatus usageStatus, int inputs,
                                                   Long latency, AnswerUsageMetadata usage) {
        return new QueryTransformationExecution(policy.version(), status, applicability,
                usageStatus, inputs, latency,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.totalTokens());
    }

    private static QueryTransformationStatus validate(String original, String rewrite,
                                                      List<String> protectedTokens) {
        if (rewrite == null || rewrite.isBlank() || rewrite.codePoints().anyMatch(Character::isISOControl)) {
            return QueryTransformationStatus.FALLBACK_PROVIDER_INVALID;
        }
        if (rewrite.codePointCount(0, rewrite.length()) > MAX_CODE_POINTS
                || CjkBigramProjector.tokens(rewrite).size() > MAX_PROJECTED_TERMS) {
            return QueryTransformationStatus.FALLBACK_OUTPUT_OVER_LIMIT;
        }
        String normalizedOriginal = Normalizer.normalize(original.strip(), Normalizer.Form.NFC);
        if (rewrite.equalsIgnoreCase(normalizedOriginal)
                || CjkBigramProjector.tokens(rewrite).equals(CjkBigramProjector.tokens(normalizedOriginal))) {
            return QueryTransformationStatus.NO_OP_DUPLICATE;
        }
        if (protectedTokens.stream().anyMatch(token -> !rewrite.contains(token))) {
            return QueryTransformationStatus.FALLBACK_EXACT_TOKEN_LOSS;
        }
        return null;
    }

    static List<String> protectedTokens(String query) {
        Matcher matcher = TECHNICAL_TOKEN.matcher(query);
        Set<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) tokens.add(matcher.group());
        return List.copyOf(tokens);
    }

    private static EvidenceBundle merge(EvidenceBundle original, EvidenceBundle rewritten) {
        if (rewritten == null || !original.workspace().equals(rewritten.workspace())) return null;
        int maxItems = original.budget().maxItems();
        int maxCharacters = original.budget().maxCharacters();
        List<EvidenceItem> merged = new ArrayList<>();
        Set<String> identities = new LinkedHashSet<>();
        Set<String> originalIdentities = new LinkedHashSet<>();
        original.items().forEach(item -> originalIdentities.add(item.stableIdentity()));
        int characters = 0;
        boolean excluded = false;
        int maxRank = Math.max(original.items().size(), rewritten.items().size());
        for (int rank = 0; rank < maxRank && merged.size() < maxItems; rank++) {
            if (rank < original.items().size()) {
                AddResult result = add(original.items().get(rank), merged, identities, characters,
                        maxCharacters);
                characters = result.characters();
                excluded |= result.excluded();
            }
            if (rank < rewritten.items().size() && merged.size() < maxItems) {
                EvidenceItem rewrittenItem = rewritten.items().get(rank);
                if (!originalIdentities.contains(rewrittenItem.stableIdentity())) {
                    AddResult result = add(rewrittenItem, merged, identities, characters,
                            maxCharacters);
                    characters = result.characters();
                    excluded |= result.excluded();
                }
            }
        }
        excluded |= maxRank > 0 && merged.size() >= maxItems
                && identities.size() < distinctIdentityCount(original, rewritten);
        boolean truncated = excluded
                || original.budget().truncated() || rewritten.budget().truncated();
        EvidenceBudget budget = new EvidenceBudget(maxItems, maxCharacters, merged.size(),
                characters, (characters + 3) / 4, truncated);
        return new EvidenceBundle(original.query(), original.mode(), original.workspace(), merged,
                budget, original.searchedCandidateCount() + rewritten.searchedCandidateCount(),
                original.rejectedCandidateCount() + rewritten.rejectedCandidateCount(),
                merged.isEmpty(), original.diagnostics());
    }

    private static AddResult add(EvidenceItem item, List<EvidenceItem> result,
                                 Set<String> identities, int characters, int maxCharacters) {
        if (!identities.add(item.stableIdentity())) return new AddResult(characters, false);
        int length = item.content().codePointCount(0, item.content().length());
        if (characters + length > maxCharacters) return new AddResult(characters, true);
        result.add(item);
        return new AddResult(characters + length, false);
    }

    private static int distinctIdentityCount(EvidenceBundle original, EvidenceBundle rewritten) {
        Set<String> identities = new LinkedHashSet<>();
        original.items().forEach(item -> identities.add(item.stableIdentity()));
        rewritten.items().forEach(item -> identities.add(item.stableIdentity()));
        return identities.size();
    }

    private record AddResult(int characters, boolean excluded) { }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
