package org.km.llmwiki.rag;

import java.util.List;
import java.util.Map;

/**
 * One candidate's measured outcome for one evaluation query (#390). Intrinsic fields are
 * computed from the retrieval execution itself; the delta fields (additional relevant/noise)
 * are attached afterwards against the ORIGINAL_QUERY baseline and are always reported
 * separately so a pool gain is never conflated with window-ordering changes. Per-input channel
 * observations expose which modality channel surfaced which candidate, so gains and losses can
 * be attributed to lexical/vector/graph candidate generation rather than to fusion.
 */
record VariantQueryRun(String candidate, String queryId, String queryClass,
                       List<String> inputs, List<String> inputOrigins, List<String> events,
                       List<String> pool, List<String> mergedOrder,
                       double poolRecall, double recallAtK, double mrr, double precisionAtK,
                       int noiseCount, boolean insufficientEvidence, int fanOut,
                       int providerCallAttempts, Map<String, List<String>> rejectionCodes,
                       boolean vectorUnavailable, boolean graphUnavailable,
                       List<InputObservation> inputObservations,
                       List<String> additionalRelevantInPool,
                       List<String> additionalRelevantInBundle,
                       List<String> additionalNoiseInBundle) {

    /** Per-retrieval-input observation: per-channel candidate identities + final bundle. */
    record InputObservation(String origin, String queryText,
                            Map<String, List<String>> channelCandidates,
                            List<String> bundleOrder) {
    }

    static VariantQueryRun intrinsic(String candidate, String queryId, String queryClass,
                                     List<String> inputs, List<String> inputOrigins,
                                     List<String> events, List<String> pool,
                                     List<String> mergedOrder, double poolRecall,
                                     double recallAtK, double mrr, double precisionAtK,
                                     int noiseCount, boolean insufficientEvidence, int fanOut,
                                     int providerCallAttempts,
                                     Map<String, List<String>> rejectionCodes,
                                     boolean vectorUnavailable, boolean graphUnavailable,
                                     List<InputObservation> inputObservations) {
        return new VariantQueryRun(candidate, queryId, queryClass, inputs, inputOrigins, events,
                pool, mergedOrder, poolRecall, recallAtK, mrr, precisionAtK, noiseCount,
                insufficientEvidence, fanOut, providerCallAttempts, rejectionCodes,
                vectorUnavailable, graphUnavailable, inputObservations, List.of(), List.of(),
                List.of());
    }

    VariantQueryRun withDeltas(List<String> relevantInPool, List<String> relevantInBundle,
                               List<String> extraNoise) {
        return new VariantQueryRun(candidate, queryId, queryClass, inputs, inputOrigins, events,
                pool, mergedOrder, poolRecall, recallAtK, mrr, precisionAtK, noiseCount,
                insufficientEvidence, fanOut, providerCallAttempts, rejectionCodes,
                vectorUnavailable, graphUnavailable, inputObservations, relevantInPool,
                relevantInBundle, extraNoise);
    }
}
