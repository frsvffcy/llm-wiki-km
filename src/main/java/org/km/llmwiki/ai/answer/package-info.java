/**
 * Provider-neutral Answer domain boundary for the completed Sprint 6 stateless Ask/Answer MVP.
 *
 * <p>This package owns only immutable request/result metadata, typed failure semantics, and
 * explicit offline test doubles, bounded evidence context/citation assembly, and the versioned
 * grounded-answer prompt/response contract, together with the provider adapter boundary. Ask
 * orchestration belongs to {@code ai.ask}; REST and Browser UI belong to their application/web
 * surfaces. Search and FTS-backed Retrieval belong to {@code search}/{@code rag}; persistent
 * knowledge mutation remains in the Proposal → Draft → Human Review → Publish workflow. No
 * provider SDK, persistence, filesystem, Search, or Retrieval implementation belongs here.
 */
package org.km.llmwiki.ai.answer;
