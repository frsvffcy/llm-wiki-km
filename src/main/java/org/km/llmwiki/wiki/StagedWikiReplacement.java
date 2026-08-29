package org.km.llmwiki.wiki;

import java.nio.file.Path;

record StagedWikiReplacement(Path temporaryPath, Path targetPath, byte[] beforeBytes,
                             String beforeHash, String afterHash) {
    StagedWikiReplacement {
        beforeBytes = beforeBytes.clone();
    }

    @Override
    public byte[] beforeBytes() {
        return beforeBytes.clone();
    }
}
