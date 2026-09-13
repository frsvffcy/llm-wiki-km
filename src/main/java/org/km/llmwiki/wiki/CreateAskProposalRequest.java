package org.km.llmwiki.wiki;

import java.util.List;

/**
 * Explicit human request to turn one Ask result into a governed Proposal (#374). The
 * Ask itself stays read-only; this is the separate, user-triggered mutation command.
 */
public record CreateAskProposalRequest(String question, String answerText, String provider,
                                       String model, List<AskCitationInput> citations) {
}
