package org.km.llmwiki.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeTagPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizesNodeTagsLikeTheDraftConverter() throws Exception {
        var data = objectMapper.readTree("""
                {"tags": ["Java", " framework ", "java"]}""");

        assertThat(KnowledgeTagPolicy.normalizedTags(data)).containsExactly("framework", "java");
    }

    @Test
    void missingTagsNodeYieldsEmptyList() throws Exception {
        assertThat(KnowledgeTagPolicy.normalizedTags(objectMapper.readTree("{}"))).isEmpty();
        assertThat(KnowledgeTagPolicy.normalizedTags((List<String>) null)).isEmpty();
    }

    @Test
    void normalizesHumanSubmittedTagsWithTheSameRules() {
        assertThat(KnowledgeTagPolicy.normalizedTags(List.of("  部署手冊 ", "部署手冊", "CI")))
                .containsExactly("ci", "部署手冊");
    }

    @Test
    void rejectsNonArrayTagsNode() throws Exception {
        var data = objectMapper.readTree("""
                {"tags": "not-an-array"}""");

        assertThatThrownBy(() -> KnowledgeTagPolicy.normalizedTags(data))
                .isInstanceOf(WikiDraftValidationException.class);
    }

    @Test
    void rejectsBlankAndNullTags() {
        assertThatThrownBy(() -> KnowledgeTagPolicy.normalizedTags(List.of("ok", "   ")))
                .isInstanceOf(WikiDraftValidationException.class);
        List<String> withNull = new ArrayList<>();
        withNull.add("ok");
        withNull.add(null);
        assertThatThrownBy(() -> KnowledgeTagPolicy.normalizedTags(withNull))
                .isInstanceOf(WikiDraftValidationException.class);
    }

    @Test
    void enforcesBoundedTagsFailClosed() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < KnowledgeTagPolicy.MAX_TAGS + 1; i++) {
            tooMany.add("tag-" + i);
        }
        assertThatThrownBy(() -> KnowledgeTagPolicy.normalizedTags(tooMany))
                .isInstanceOf(WikiDraftValidationException.class);

        assertThatThrownBy(() -> KnowledgeTagPolicy.normalizedTags(List.of("x".repeat(KnowledgeTagPolicy.MAX_TAG_LENGTH + 1))))
                .isInstanceOf(WikiDraftValidationException.class);
    }
}
