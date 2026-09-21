package org.km.llmwiki.wiki;

import com.fasterxml.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tags 的單一正規化 authority（#569）。
 *
 * <p>所有進入 Wiki 目的 metadata 的 tags——不論來自 LLM normalized data 或人類
 * PATCH——都經過同一條規則：NFC＋strip＋空白摺疊、小寫、去重、確定性排序、有界。
 * {@link WikiDraftConverter} 與 proposal tags mutation 共用此 policy，兩條路徑
 * 不可能漂移出兩種「合法 tags」定義。
 */
public final class KnowledgeTagPolicy {

    /** 單一 proposal/draft 允許的 tags 上限（fail-closed bounded contract）。 */
    public static final int MAX_TAGS = 50;

    /** 單一 tag 正規化後的最大字元數。 */
    public static final int MAX_TAG_LENGTH = 80;

    private KnowledgeTagPolicy() {
    }

    /**
     * 正規化 proposal normalized data 中的 {@code tags} 陣列。
     *
     * @param data normalized data JSON object
     * @return 確定性排序、去重、小寫的 tags；欄位缺席時為空 list
     * @throws WikiDraftValidationException 形狀不合法、超過上限時（皆為 {@link IllegalArgumentException}）
     */
    public static List<String> normalizedTags(JsonNode data) {
        return normalizedNodeTags(nodeTags(data));
    }

    /**
     * 正規化人類直接提交的 tags list（proposal tags PATCH）。
     *
     * @param tags 人類提交的原始 tags；{@code null} 視為清空為空 list
     * @return 確定性排序、去重、小寫的 tags
     * @throws WikiDraftValidationException 形狀不合法、超過上限時
     */
    public static List<String> normalizedTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> items = new ArrayList<>(tags.size());
        for (String value : tags) {
            if (value == null) {
                throw invalid("tags must contain only strings");
            }
            items.add(normalizeInline(value, "tags"));
        }
        return dedupLower(items);
    }

    private static JsonNode nodeTags(JsonNode data) {
        if (data == null || data.isNull()) {
            return null;
        }
        return data.get("tags");
    }

    private static List<String> normalizedNodeTags(JsonNode values) {
        if (values == null || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw invalid("tags must be an array of strings");
        }
        List<String> items = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid("tags must contain only strings");
            }
            items.add(normalizeInline(value.asText(), "tags"));
        }
        return dedupLower(items);
    }

    private static List<String> dedupLower(List<String> items) {
        Set<String> normalized = new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (String item : items) {
            normalized.add(item.toLowerCase(Locale.ROOT));
        }
        if (normalized.size() > MAX_TAGS) {
            throw invalid("tags must contain at most " + MAX_TAGS + " entries");
        }
        for (String tag : normalized) {
            if (tag.length() > MAX_TAG_LENGTH) {
                throw invalid("each tag must contain at most " + MAX_TAG_LENGTH + " characters");
            }
        }
        return List.copyOf(normalized);
    }

    static String normalizeInline(String value, String field) {
        if (value == null) {
            throw invalid(field + " must not be null");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return normalized;
    }

    private static WikiDraftValidationException invalid(String message) {
        return new WikiDraftValidationException(WikiDraftValidationException.Reason.INVALID_NORMALIZED_DATA, message);
    }
}
