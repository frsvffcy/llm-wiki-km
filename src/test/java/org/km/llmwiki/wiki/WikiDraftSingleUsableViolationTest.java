package org.km.llmwiki.wiki;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #601：storage conflict 判別只認 V35 partial unique index，其他約束違反一律 fail-closed。
 */
@Tag("unit")
class WikiDraftSingleUsableViolationTest {

    @Test
    void recognizesSingleUsableDraftViolationThroughWrappers() {
        // jOOQ 包裝後的訊息仍保留 SQLite 原始約束敘述（driver 細節不列入判別）。
        var wrapped = new DataAccessException(
                "SQL [insert into wiki_draft ...]; [SQLITE_CONSTRAINT_UNIQUE] "
                        + "A UNIQUE constraint failed (UNIQUE constraint failed: "
                        + "wiki_draft.workspace_id, wiki_draft.proposal_id)",
                new RuntimeException("statement failed"));

        assertThat(WikiDraftRepository.isSingleUsableDraftViolation(wrapped)).isTrue();
    }

    @Test
    void rejectsOtherConstraintsAndNullsFailClosed() {
        var primaryKey = new DataAccessException(
                "SQL [insert into wiki_draft ...]; [SQLITE_CONSTRAINT_PRIMARYKEY] "
                        + "A PRIMARY KEY constraint failed (UNIQUE constraint failed: wiki_draft.id)",
                new RuntimeException("boom"));
        assertThat(WikiDraftRepository.isSingleUsableDraftViolation(primaryKey)).isFalse();

        var otherTable = new DataAccessException(
                "SQL [insert ...]; UNIQUE constraint failed: knowledge_proposal.source_dedup_hash",
                new RuntimeException("boom"));
        assertThat(WikiDraftRepository.isSingleUsableDraftViolation(otherTable)).isFalse();

        assertThat(WikiDraftRepository.isSingleUsableDraftViolation(null)).isFalse();
        assertThat(WikiDraftRepository.isSingleUsableDraftViolation(
                new DataAccessException("no detail", new RuntimeException()))).isFalse();
    }
}
