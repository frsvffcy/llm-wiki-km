package org.km.llmwiki.wiki;

import java.util.List;

/** Exact-match, read-only catalog used by deterministic target resolution. */
public interface WikiTargetCatalog {

    boolean existsAtCanonicalPath(long workspaceId, String logicalRelativePath);

    List<WikiTargetRecord> findExact(WikiTargetReference reference);
}
