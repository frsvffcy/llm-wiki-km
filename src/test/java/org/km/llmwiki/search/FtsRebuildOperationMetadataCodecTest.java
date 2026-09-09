package org.km.llmwiki.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class FtsRebuildOperationMetadataCodecTest {

    @Test
    void encodesEachSupportedCorpusInCanonicalBoundedShape() {
        assertThat(FtsRebuildOperationMetadataCodec.encode(SearchCorpus.WIKI))
                .isEqualTo("{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"WIKI\"}");
        assertThat(FtsRebuildOperationMetadataCodec.encode(SearchCorpus.SOURCE))
                .isEqualTo("{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"SOURCE\"}");
        assertThat(FtsRebuildOperationMetadataCodec.encode(SearchCorpus.ALL))
                .isEqualTo("{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"ALL\"}");
    }

    @Test
    void decodesOnlyTheAllowListedSchemaAndFields() {
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"SOURCE\"}"))
                .contains(SearchCorpus.SOURCE);
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"fts-rebuild-operation-v2\",\"corpus\":\"WIKI\"}"))
                .isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"WIKI\",\"secret\":\"x\"}"))
                .isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"UNKNOWN\"}"))
                .isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"WIKI\",\"corpus\":\"SOURCE\"}"))
                .isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{ \"schema\": \"fts-rebuild-operation-v1\", \"corpus\": \"WIKI\" }"))
                .isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{\"corpus\":\"WIKI\",\"schema\":\"fts-rebuild-operation-v1\"}"))
                .isEmpty();
    }

    @Test
    void treatsMissingMalformedAndOversizedMetadataAsUnknown() {
        assertThat(FtsRebuildOperationMetadataCodec.decode(null)).isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode("not-json")).isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode(" ")).isEmpty();
        assertThat(FtsRebuildOperationMetadataCodec.decode(
                "{\"schema\":\"fts-rebuild-operation-v1\",\"corpus\":\"WIKI\"}" + "x".repeat(256)))
                .isEmpty();
    }

    @Test
    void refusesNullCorpusForNewJobs() {
        assertThatThrownBy(() -> FtsRebuildOperationMetadataCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
