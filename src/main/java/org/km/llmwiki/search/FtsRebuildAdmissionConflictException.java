package org.km.llmwiki.search;

/** Typed, stable rejection of a duplicate FTS rebuild admission for one workspace. */
public class FtsRebuildAdmissionConflictException extends RuntimeException {

    public FtsRebuildAdmissionConflictException(SearchCorpus requested,
                                                java.util.List<SearchCorpus> overlapping) {
        super("FTS rebuild admission rejected for corpus " + requested + ": overlapping corpora "
                + overlapping + " are already queued or running for this workspace");
    }
}
