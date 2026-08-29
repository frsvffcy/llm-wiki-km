package org.km.llmwiki.wiki;

import java.nio.file.Path;

record StagedWikiFile(Path temporaryPath, Path targetPath, String contentHash) {
}
