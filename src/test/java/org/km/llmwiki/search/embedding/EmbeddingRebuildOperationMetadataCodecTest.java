package org.km.llmwiki.search.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.km.llmwiki.search.SearchCorpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class EmbeddingRebuildOperationMetadataCodecTest {

    @Test
    void encodesEachSupportedCorpusInCanonicalBoundedShape() {
        assertThat(EmbeddingRebuildOperationMetadataCodec.encode(SearchCorpus.WIKI))
                .isEqualTo("{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"WIKI\"}");
        assertThat(EmbeddingRebuildOperationMetadataCodec.encode(SearchCorpus.SOURCE))
                .isEqualTo("{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"SOURCE\"}");
        assertThat(EmbeddingRebuildOperationMetadataCodec.encode(SearchCorpus.ALL))
                .isEqualTo("{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"ALL\"}");
    }

    @Test
    void decodesOnlyTheAllowListedSchemaAndFields() {
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"SOURCE\"}"))
                .contains(SearchCorpus.SOURCE);
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"embedding-rebuild-operation-v2\",\"corpus\":\"WIKI\"}"))
                .isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"WIKI\",\"secret\":\"x\"}"))
                .isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"UNKNOWN\"}"))
                .isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"WIKI\",\"corpus\":\"SOURCE\"}"))
                .isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{ \"schema\": \"embedding-rebuild-operation-v1\", \"corpus\": \"WIKI\" }"))
                .isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{\"corpus\":\"WIKI\",\"schema\":\"embedding-rebuild-operation-v1\"}"))
                .isEmpty();
    }

    @Test
    void treatsMissingMalformedAndOversizedMetadataAsUnknown() {
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(null)).isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode("not-json")).isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(" ")).isEmpty();
        assertThat(EmbeddingRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"embedding-rebuild-operation-v1\",\"corpus\":\"WIKI\"}" + "x".repeat(256)))
                .isEmpty();
    }

    @Test
    void refusesNullCorpusForNewJobs() {
        assertThatThrownBy(() -> EmbeddingRebuildOperationMetadataCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
