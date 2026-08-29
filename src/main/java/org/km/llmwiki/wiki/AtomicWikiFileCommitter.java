package org.km.llmwiki.wiki;

import java.io.IOException;
import java.nio.file.Path;

/** Injectable no-replace atomic filesystem primitive. */
@FunctionalInterface
interface AtomicWikiFileCommitter {
    void commit(Path stagedFile, Path target) throws IOException;
}
