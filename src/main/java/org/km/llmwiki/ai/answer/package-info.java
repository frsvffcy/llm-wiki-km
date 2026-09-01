/**
 * Provider-neutral Answer domain boundary introduced for Sprint 6.
 *
 * <p>This package owns only immutable request/result metadata, typed failure semantics, and
 * explicit offline test doubles, bounded evidence context/citation assembly, and the versioned
 * grounded-answer prompt/response contract. Real provider adapters, Ask orchestration, and
 * REST/UI surfaces remain later work.
 * No provider SDK, persistence, filesystem, Search, or Retrieval implementation belongs here.
 */
package org.km.llmwiki.ai.answer;
