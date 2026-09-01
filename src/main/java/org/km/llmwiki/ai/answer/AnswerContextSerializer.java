package org.km.llmwiki.ai.answer;

import java.util.Objects;

/**
 * Serializes context as labelled JSON data. It has no system prompt semantics and never executes
 * Markdown, HTML, scripts, or instruction-like evidence text.
 */
public final class AnswerContextSerializer {

    private AnswerContextSerializer() {
    }

    public static String serialize(String question, AnswerContext context) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (question.length() > 4_000) {
            throw new IllegalArgumentException("question must not exceed 4000 characters");
        }
        Objects.requireNonNull(context, "context must not be null");

        StringBuilder output = new StringBuilder("ANSWER_CONTEXT_V1\n");
        output.append("USER_QUESTION_JSON=").append(json(question)).append("\n");
        output.append("EVIDENCE_DATA_UNTRUSTED_JSON=[");
        for (int index = 0; index < context.blocks().size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            AnswerContextBlock block = context.blocks().get(index);
            output.append('{')
                    .append("\"citationId\":").append(json(block.citationId()))
                    .append(",\"evidenceKind\":").append(json(block.evidenceKind().name()))
                    .append(",\"authorityIdentity\":").append(json(block.authorityIdentity()))
                    .append(",\"contentTruncated\":").append(block.contentTruncated())
                    .append(",\"content\":").append(json(block.content()))
                    .append(",\"provenance\":").append(provenance(block.provenance()))
                    .append('}');
        }
        return output.append("]\n").toString();
    }

    private static String provenance(AnswerContextProvenance provenance) {
        if (provenance instanceof AnswerContextProvenance.Wiki wiki) {
            return "{\"title\":" + json(wiki.title()) + ",\"path\":" + json(wiki.path())
                    + (wiki.revision() == null ? "" : ",\"revision\":" + wiki.revision()) + '}';
        }
        AnswerContextProvenance.Source source = (AnswerContextProvenance.Source) provenance;
        return "{\"documentName\":" + json(source.documentName())
                + ",\"documentId\":" + source.documentId()
                + ",\"sourceChunkId\":" + source.sourceChunkId()
                + ",\"chunkNo\":" + source.chunkNo()
                + nullableJsonField("pageNo", source.pageNo())
                + nullableJsonField("section", source.section())
                + nullableJsonField("headingPath", source.headingPath()) + '}';
    }

    private static String nullableJsonField(String name, Object value) {
        return value == null ? "" : ",\"" + name + "\":" + (value instanceof String ? json((String) value) : value);
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        escaped.append(String.format("\\u%04x", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
