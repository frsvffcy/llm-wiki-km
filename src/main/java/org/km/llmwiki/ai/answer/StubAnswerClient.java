package org.km.llmwiki.ai.answer;

import java.util.Objects;

/**
 * Explicit deterministic test double. It is not a Spring bean and is never selected implicitly
 * as a production provider.
 */
public final class StubAnswerClient implements AnswerClient {

    private final AnswerResult result;
    private final AnswerFailure failure;

    public StubAnswerClient(AnswerResult result) {
        this.result = Objects.requireNonNull(result, "result must not be null");
        this.failure = null;
    }

    public StubAnswerClient(AnswerFailure failure) {
        this.result = null;
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public static StubAnswerClient returning(AnswerResult result) {
        return new StubAnswerClient(result);
    }

    public static StubAnswerClient failing(AnswerFailureType type, String diagnostic) {
        return new StubAnswerClient(new AnswerFailure(type, diagnostic));
    }

    @Override
    public AnswerResult generate(AnswerRequest request) {
        requireRequest(request);
        if (failure != null) {
            throw new AnswerClientException(failure);
        }
        return result;
    }

    private static void requireRequest(AnswerRequest request) {
        if (request == null) {
            throw new AnswerClientException(AnswerFailureType.LOCAL_VALIDATION,
                    "answer request is required");
        }
    }
}
