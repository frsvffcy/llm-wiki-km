package org.km.llmwiki.ai.answer;

/** Safe production default until a real provider adapter is explicitly configured. */
public final class DisabledAnswerClient implements AnswerClient {

    @Override
    public AnswerResult generate(AnswerRequest request) {
        if (request == null) {
            throw new AnswerClientException(AnswerFailureType.LOCAL_VALIDATION,
                    "answer request is required");
        }
        throw new AnswerClientException(AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                "no real answer provider is configured or enabled");
    }
}
