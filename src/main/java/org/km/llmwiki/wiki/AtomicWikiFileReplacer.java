package org.km.llmwiki.wiki;

import java.io.IOException;
import java.nio.file.Path;

/** Injectable atomic-replace filesystem primitive for an existing MERGE target. */
@FunctionalInterface
interface AtomicWikiFileReplacer {
    void replace(Path stagedFile, Path target) throws IOException;
}
