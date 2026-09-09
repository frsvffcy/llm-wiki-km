package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class DocumentParserResourceContractTest {

    @Test
    void limitsRequireFinitePositiveValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentParserLimits(0, 1, 1, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentParserLimits(1, 0, 1, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentParserLimits(1, 1, 0, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentParserLimits(1, 1, -1, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentParserLimits(1, 1, 1, 0));
    }

    @Test
    void propertiesRejectNegativeUnlimitedAndAboveCeilingValues() {
        ExtractionResourceProperties properties = new ExtractionResourceProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxInputBytes(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxInputBytes(Long.MAX_VALUE));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxOutputCharacters(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxOutputCharacters(Integer.MAX_VALUE));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxMetadataCharacters(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxMetadataCharacters(Integer.MAX_VALUE));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxStructureBlocks(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxStructureBlocks(Integer.MAX_VALUE));

        assertThat(properties.limits()).isEqualTo(new DocumentParserLimits(
                ExtractionResourceProperties.ABSOLUTE_MAX_INPUT_BYTES,
                ExtractionResourceProperties.ABSOLUTE_MAX_OUTPUT_CHARACTERS,
                ExtractionResourceProperties.ABSOLUTE_MAX_METADATA_CHARACTERS,
                100_000));
    }

    @Test
    void boundedInputStreamAllowsExactlyTheConfiguredBytesButRejectsTheNextByte() throws IOException {
        BoundedInputStream input = new BoundedInputStream(
                new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), 5);
        byte[] buffer = new byte[5];

        assertThat(input.read(buffer)).isEqualTo(5);
        assertThat(buffer).isEqualTo("12345".getBytes(StandardCharsets.UTF_8));
        assertThat(input.read()).isEqualTo(-1);

        BoundedInputStream overLimit = new BoundedInputStream(
                new ByteArrayInputStream("123456".getBytes(StandardCharsets.UTF_8)), 5);
        assertThat(overLimit.read(buffer)).isEqualTo(5);
        assertThatThrownBy(overLimit::read)
                .isInstanceOf(DocumentParserResourceLimitException.class)
                .extracting(exception -> ((DocumentParserResourceLimitException) exception).resource())
                .isEqualTo(DocumentParserResourceLimitException.Resource.INPUT_BYTES);
    }

    @Test
    void metadataFilterCommitsOnlyWritesThatRemainWithinTheBound() {
        BoundedMetadataWriteFilter filter = new BoundedMetadataWriteFilter(6);
        Map<String, String[]> metadata = new LinkedHashMap<>();

        filter.set("a", "12345", metadata);
        assertThat(metadata.get("a")).containsExactly("12345");

        assertThatThrownBy(() -> filter.add("b", "x", metadata))
                .isInstanceOf(MetadataLimitExceededException.class);
        assertThat(metadata).containsOnlyKeys("a");
    }
}
