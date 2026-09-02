package org.km.llmwiki.search.vector.sqlite;

import org.km.llmwiki.search.vector.VectorExtensionLoadStatus;

import java.nio.file.Path;
import java.sql.Connection;

@FunctionalInterface
interface VectorExtensionLoader {

    LoadResult load(Connection connection, Path extensionPath);

    record LoadResult(VectorExtensionLoadStatus status, String detail) {
    }
}
