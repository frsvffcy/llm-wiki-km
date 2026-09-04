package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Same-filesystem atomic rename that replaces exactly one existing MERGE target. */
@Component
class MoveAtomicWikiFileReplacer implements AtomicWikiFileReplacer {
    @Override
    public void replace(Path stagedFile, Path target) throws IOException {
        Files.move(stagedFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
