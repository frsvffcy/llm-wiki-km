package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE;

/** jOOQ-only read boundary for Published Wiki metadata used by rebuildable projections. */
@Repository
public class PublishedWikiRepository {

    private final DSLContext dsl;

    public PublishedWikiRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<StoredPublishedWiki> findPublishedById(long workspaceId, long knowledgePageId) {
        return dsl.selectFrom(KNOWLEDGE_PAGE)
                .where(KNOWLEDGE_PAGE.ID.eq(Math.toIntExact(knowledgePageId)))
                .and(KNOWLEDGE_PAGE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(KNOWLEDGE_PAGE.STATUS.eq(PageStatus.PUBLISHED.name()))
                .fetchOptional(this::map);
    }

    public Optional<StoredPublishedWiki> findPublishedByKnowledgeId(long workspaceId, String knowledgeId) {
        return dsl.selectFrom(KNOWLEDGE_PAGE)
                .where(KNOWLEDGE_PAGE.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(KNOWLEDGE_PAGE.KNOWLEDGE_ID.eq(knowledgeId))
                .and(KNOWLEDGE_PAGE.STATUS.eq(PageStatus.PUBLISHED.name()))
                .fetchOptional(this::map);
    }

    private StoredPublishedWiki map(org.km.llmwiki.persistence.jooq.generated.tables.records.KnowledgePageRecord record) {
        return new StoredPublishedWiki(record.getId().longValue(), record.getWorkspaceId().longValue(),
                record.getKnowledgeId(), record.getTitle(), record.getNormalizedTitle(),
                WikiPageType.valueOf(record.getType()), record.getMarkdownPath(),
                PageStatus.valueOf(record.getStatus()), record.getContentHash(), record.getRevision(),
                record.getCreatedAt(), record.getUpdatedAt());
    }
}
