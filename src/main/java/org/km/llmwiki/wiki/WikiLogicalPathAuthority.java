package org.km.llmwiki.wiki;

/** Narrow, side-effect-free view of the active workspace Wiki path authority. */
@FunctionalInterface
public interface WikiLogicalPathAuthority {

    String resolveLogicalPath(WikiPageType type, String title);
}
