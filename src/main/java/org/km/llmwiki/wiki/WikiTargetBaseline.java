package org.km.llmwiki.wiki;

/** Immutable content baseline captured from the #90 target snapshot at Draft creation time. */
record WikiTargetBaseline(String content, String contentHash) {

    WikiTargetBaseline {
        if (content == null || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Wiki target baseline requires content and SHA-256 hash");
        }
    }
}
