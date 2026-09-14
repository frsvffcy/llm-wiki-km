package org.km.llmwiki.ai.provider;

import org.km.llmwiki.ai.answer.provider.openai.OpenAiCompatibleAnswerProperties;
import org.km.llmwiki.ai.embedding.provider.openai.OpenAiCompatibleEmbeddingProperties;
import org.km.llmwiki.ai.query.provider.openai.OpenAiCompatibleQueryRewriteProperties;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

/**
 * Application-owned provider egress classification service. It derives the destination
 * classification for the answer and embedding provider boundaries from the backend's current
 * configuration and the shared #281 transport policy, and projects the data-category
 * disclosure. It is a transparency boundary only: it never exposes credentials, raw endpoints,
 * or provider details, it is not a readiness/capability surface, and it never changes
 * provider behavior.
 */
@org.springframework.stereotype.Service
public class ProviderEgressService {

    private final OpenAiCompatibleAnswerProperties answerProperties;
    private final OpenAiCompatibleEmbeddingProperties embeddingProperties;
    private final OpenAiCompatibleQueryRewriteProperties rewriteProperties;

    public ProviderEgressService(OpenAiCompatibleAnswerProperties answerProperties,
                                 OpenAiCompatibleEmbeddingProperties embeddingProperties) {
        this(answerProperties, embeddingProperties, new OpenAiCompatibleQueryRewriteProperties());
    }

    @Autowired
    public ProviderEgressService(OpenAiCompatibleAnswerProperties answerProperties,
                                 OpenAiCompatibleEmbeddingProperties embeddingProperties,
                                 OpenAiCompatibleQueryRewriteProperties rewriteProperties) {
        this.answerProperties = answerProperties;
        this.embeddingProperties = embeddingProperties;
        this.rewriteProperties = rewriteProperties;
    }

    /** All provider-boundary descriptors the Browser may safely render. */
    public List<ProviderEgressDescriptor> descriptors() {
        return List.of(answerDescriptor(), embeddingDescriptor(), rewriteDescriptor());
    }

    private ProviderEgressDescriptor answerDescriptor() {
        if (!answerProperties.isEnabled()) {
            return new ProviderEgressDescriptor(
                    ProviderEgressDescriptor.ProviderPurpose.ANSWER,
                    ProviderDestination.DISABLED, null, null, java.util.List.of());
        }
        ProviderDestination destination = ProviderEndpointSecurityPolicy.classify(
                answerProperties.getBaseUrl(), answerProperties.isAllowInsecureTransport());
        // A configuration the transport policy rejects can never be used for egress; the
        // disclosure must not claim any data category for an invalid destination.
        java.util.List<ProviderEgressCategory> categories =
                destination == ProviderDestination.UNAVAILABLE_OR_INVALID
                        ? java.util.List.of()
                        : java.util.List.of(
                        ProviderEgressCategory.QUESTION_TEXT,
                        ProviderEgressCategory.EVIDENCE_CONTEXT_REPRESENTATION,
                        ProviderEgressCategory.INSTRUCTION_CONTEXT,
                        ProviderEgressCategory.GENERATION_SETTINGS,
                        ProviderEgressCategory.PROVIDER_RESPONSE_METADATA);
        return new ProviderEgressDescriptor(
                ProviderEgressDescriptor.ProviderPurpose.ANSWER, destination,
                answerProperties.getProvider(), answerProperties.getModel(), categories);
    }

    private ProviderEgressDescriptor embeddingDescriptor() {
        if (!embeddingProperties.isEnabled()) {
            return new ProviderEgressDescriptor(
                    ProviderEgressDescriptor.ProviderPurpose.EMBEDDING,
                    ProviderDestination.DISABLED, null, null, java.util.List.of());
        }
        ProviderDestination destination = ProviderEndpointSecurityPolicy.classify(
                embeddingProperties.getBaseUrl(), embeddingProperties.isAllowInsecureTransport());
        // Same invariant as the answer boundary: a policy-invalid destination can never be
        // used for egress, so the disclosure must not claim any data category for it.
        java.util.List<ProviderEgressCategory> categories =
                destination == ProviderDestination.UNAVAILABLE_OR_INVALID
                        ? java.util.List.of()
                        : java.util.List.of(
                        ProviderEgressCategory.EMBEDDING_INPUT_REPRESENTATION);
        return new ProviderEgressDescriptor(
                ProviderEgressDescriptor.ProviderPurpose.EMBEDDING, destination,
                embeddingProperties.getProvider(), embeddingProperties.getModel(), categories);
    }

    private ProviderEgressDescriptor rewriteDescriptor() {
        if (!rewriteProperties.isEnabled()) {
            return new ProviderEgressDescriptor(
                    ProviderEgressDescriptor.ProviderPurpose.QUERY_REWRITE,
                    ProviderDestination.DISABLED, null, null, java.util.List.of());
        }
        ProviderDestination destination = ProviderEndpointSecurityPolicy.classify(
                rewriteProperties.getBaseUrl(), rewriteProperties.isAllowInsecureTransport());
        java.util.List<ProviderEgressCategory> categories =
                destination == ProviderDestination.UNAVAILABLE_OR_INVALID ? java.util.List.of()
                        : java.util.List.of(ProviderEgressCategory.QUERY_REWRITE_INPUT,
                        ProviderEgressCategory.QUERY_REWRITE_RESPONSE_METADATA);
        return new ProviderEgressDescriptor(
                ProviderEgressDescriptor.ProviderPurpose.QUERY_REWRITE, destination,
                rewriteProperties.getProvider(), rewriteProperties.getModel(), categories);
    }
}
