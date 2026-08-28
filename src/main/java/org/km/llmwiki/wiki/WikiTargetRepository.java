package org.km.llmwiki.wiki;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static org.jooq.impl.DSL.count;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE;

/** jOOQ-backed read model for Wiki target resolution. */
@Repository
public class WikiTargetRepository implements WikiTargetCatalog {

    private final DSLContext dsl;

    public WikiTargetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean existsAtCanonicalPath(long workspaceId, String logicalRelativePath) {
        Integer matches = dsl.select(count())
                .from(KNOWLEDGE_PAGE)
                .where(KNOWLEDGE_PAGE.WORKSPACE_ID.eq((int) workspaceId))
                .and(KNOWLEDGE_PAGE.MARKDOWN_PATH.eq(logicalRelativePath))
                .fetchOne(0, Integer.class);
        return matches != null && matches > 0;
    }

    @Override
    public List<WikiTargetRecord> findExact(WikiTargetReference reference) {
        Condition match = switch (reference.kind()) {
            case STABLE_IDENTIFIER -> KNOWLEDGE_PAGE.KNOWLEDGE_ID.eq(reference.lookupValue());
            case CANONICAL_TITLE -> KNOWLEDGE_PAGE.NORMALIZED_TITLE.eq(reference.lookupValue());
        };
        return dsl.selectFrom(KNOWLEDGE_PAGE)
                .where(match)
                .orderBy(KNOWLEDGE_PAGE.WORKSPACE_ID.asc(), KNOWLEDGE_PAGE.ID.asc())
                .fetch(record -> new WikiTargetRecord(
                        record.getWorkspaceId().longValue(),
                        record.getKnowledgeId(),
                        record.getTitle(),
                        WikiPageType.from(record.getType()),
                        record.getMarkdownPath(),
                        PageStatus.valueOf(record.getStatus()),
                        record.getContentHash()));
    }
}
