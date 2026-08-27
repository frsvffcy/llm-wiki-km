package org.km.llmwiki.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The workspace template contract is {@code config/prompts/document-analysis.md}. It must contain
 * each of {@code {{document.metadata}}} and {@code {{evidence}}}; the latter is JSON data bounded
 * as untrusted evidence, never executable prompt instructions. An optional first-line marker
 * {@code <!-- prompt-version: value -->} supplies a human-managed version. Otherwise SHA-256 is
 * used as the stable version and identifier.
 */
final class DocumentAnalysisPromptTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9.]*)\\s*}}" );
    private static final Pattern VERSION = Pattern.compile("(?m)\\A<!--\\s*prompt-version:\\s*([^>\\s][^>]*)\\s*-->\\R?");
    private static final Set<String> REQUIRED_VARIABLES = Set.of("document.metadata", "evidence");
    private final ObjectMapper objectMapper;

    DocumentAnalysisPromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    DocumentAnalysisPrompt render(String content, java.nio.file.Path sourcePath,
                                  DocumentAnalysisRequest request) {
        if (content == null || content.isBlank()) {
            throw new PromptLoadException(PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID,
                    "Document analysis prompt template must not be blank");
        }
        Matcher versionMatcher = VERSION.matcher(content);
        String version = versionMatcher.find() ? versionMatcher.group(1).trim() : null;
        String template = versionMatcher.replaceFirst("");
        Map<String, String> values = valuesFor(request);
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        Set<String> found = new java.util.HashSet<>();
        while (matcher.find()) {
            String variable = matcher.group(1);
            found.add(variable);
            String value = values.get(variable);
            if (value == null) {
                throw new PromptLoadException(PromptLoadErrorCode.PROMPT_VARIABLE_MISSING,
                        "Document analysis prompt requires unsupported variable: " + variable);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (!found.containsAll(REQUIRED_VARIABLES)) {
            throw new PromptLoadException(PromptLoadErrorCode.PROMPT_VARIABLE_MISSING,
                    "Document analysis prompt must include {{document.metadata}} and {{evidence}}");
        }
        String hash = sha256(content);
        String resolvedVersion = version == null ? "sha256:" + hash : version;
        String identifier = "document-analysis@" + resolvedVersion;
        return new DocumentAnalysisPrompt(identifier, resolvedVersion, hash, sourcePath, rendered.toString());
    }

    private Map<String, String> valuesFor(DocumentAnalysisRequest request) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("documentId", request.document().documentId());
            metadata.put("originalFileName", request.document().originalFileName());
            metadata.put("mimeType", request.document().mimeType());
            metadata.put("contentHash", request.document().contentHash());
            Map<String, String> values = new LinkedHashMap<>();
            values.put("document.metadata", objectMapper.writeValueAsString(metadata));
            values.put("evidence", objectMapper.writeValueAsString(request.sourceChunkEvidence()));
            return values;
        } catch (JsonProcessingException exception) {
            throw new PromptLoadException(PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID,
                    "Document analysis prompt data could not be rendered", exception);
        }
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
