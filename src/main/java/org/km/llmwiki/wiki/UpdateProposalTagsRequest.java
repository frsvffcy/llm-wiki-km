package org.km.llmwiki.wiki;

import java.util.List;

/** 由人工審核者明確提交的 tags 調整（#569：唯一的人控 tag mutation point）。 */
public record UpdateProposalTagsRequest(List<String> tags) {
}
