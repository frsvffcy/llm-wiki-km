package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Atomically links a fully written same-directory temp inode into the final name.
 * Unlike an ATOMIC_MOVE rename on common Unix filesystems, createLink never replaces an existing target.
 */
@Component
class NoReplaceAtomicWikiFileCommitter implements AtomicWikiFileCommitter {
    @Override
    public void commit(Path stagedFile, Path target) throws IOException {
        Files.createLink(target, stagedFile);
    }
}
