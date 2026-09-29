package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.km.llmwiki.ai.LlmProposalAction;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static org.km.llmwiki.persistence.jooq.generated.Tables.WIKI_PUBLISH_OPERATION;

/**
 * Publish commit-point evidence currentness contract（#652）。
 *
 * <p>定義 CREATE / MERGE 的「evidence currentness commit point」：finalizer 交易內、
 * canonical metadata commit 前的最後一道 authoritative 證明。Request 開頭的
 * {@code requirePublishable()} 只保留作快速 fail，不得作為唯一 correctness gate。
 *
 * <p>Writer serialization：此 guard 的第一個 DB statement 必須是寫入（觸碰同一個
 * {@code wiki_publish_operation} 列的 {@code updated_at}），讓 SQLite deferred
 * transaction 在任何 validation 讀取前先取得 RESERVED write authority。若 concurrent
 * writer 已先持有 RESERVED，本交易會在 busy_timeout 內等待其提交後再讀取最新已提交
 * 狀態；若本交易先取得 RESERVED，concurrent writer 則會等待本交易提交或回滾。
 * 不將整個 DataSource 改為 IMMEDIATE，避免粗暴序列化所有交易；只收斂 finalizer
 * commit-point 交易。
 *
 * <p>驗證內容（與目前 authority 一致）：
 * <ul>
 *   <li>proposal 仍為 APPROVED 且 action 相符</li>
 *   <li>persisted ASK snapshot 仍 current（SOURCE workspace/status/parse/hash、
 *       WIKI revision/knowledgeId/canonical bytes/content hash）</li>
 * </ul>
 * Stale 一律丟 {@code PROPOSAL_INVALID}（API 為 {@code WIKI_PUBLISH_PROPOSAL_INVALID}，
 * 409），不得退化成 generic {@code METADATA_FAILURE}。Busy/lock  contention 則原樣
 * 拋出 DataAccessException，由 orchestration 補償為 {@code METADATA_FAILURE}（可重試），
 * 不得誤判為 evidence stale。
 */
@Service
public class WikiPublishCommitPointGuard {

    private final DSLContext dsl;
    private final KnowledgeProposalRepository proposalRepository;
    private final WikiDraftRepository draftRepository;
    private final AskProposalEvidenceCurrentnessValidator askEvidenceValidator;

    public WikiPublishCommitPointGuard(DSLContext dsl,
                                      KnowledgeProposalRepository proposalRepository,
                                      WikiDraftRepository draftRepository,
                                      AskProposalEvidenceCurrentnessValidator askEvidenceValidator) {
        this.dsl = dsl;
        this.proposalRepository = proposalRepository;
        this.draftRepository = draftRepository;
        this.askEvidenceValidator = askEvidenceValidator;
    }

    /**
     * 在 finalizer 交易內先取得 writer serialization，再重建 authoritative 證明。
     * 必須是交易內的第一個 DB statement；呼叫端不得在此之前做任何 SELECT。
     */
    public void verifyCommitPoint(long workspaceId, long draftId, long proposalId,
                                  long operationId, LlmProposalAction expectedAction) {
        // 1. Writer serialization：第一個 statement 即為寫入，取得 SQLite RESERVED。
        // 只觸碰同一個 FILE_COMMITTED operation 列，有界且可證明；狀態不符則進
        // RECONCILIATION_REQUIRED（非 evidence stale，不映射為 PROPOSAL_INVALID）。
        int touched = dsl.update(WIKI_PUBLISH_OPERATION)
                .set(WIKI_PUBLISH_OPERATION.UPDATED_AT, now())
                .where(WIKI_PUBLISH_OPERATION.ID.eq(Math.toIntExact(operationId)))
                .and(WIKI_PUBLISH_OPERATION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(WIKI_PUBLISH_OPERATION.DRAFT_ID.eq(Math.toIntExact(draftId)))
                .and(WIKI_PUBLISH_OPERATION.STATUS.eq(WikiPublishOperationStatus.FILE_COMMITTED.name()))
                .execute();
        if (touched != 1) {
            throw new WikiPublishException(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "Wiki publish operation is no longer in committable state");
        }

        // 2. 重讀最新 draft（不用傳入的 stale snapshot）。
        StoredWikiDraft freshDraft = draftRepository.findById(workspaceId, draftId)
                .orElseThrow(() -> new WikiPublishException(
                        WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                        "Wiki Draft is no longer visible in the active workspace"));
        if (freshDraft.status() != WikiDraftStatus.READY) {
            throw new WikiPublishException(WikiPublishException.Reason.DRAFT_NOT_READY,
                    "Only a READY Wiki Draft can be published");
        }
        if (freshDraft.action() != expectedAction || freshDraft.proposalId() != proposalId) {
            throw new WikiPublishException(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "Stored publish operation no longer matches this Draft publish intent");
        }

        // 3. Proposal 仍為 APPROVED 且 action 相符（與 preflight 同一 authority）。
        boolean proposalValid = proposalRepository.findDraftConversionSource(workspaceId, proposalId)
                .filter(source -> source.status() == KnowledgeProposalStatus.APPROVED)
                .filter(source -> source.action() == expectedAction)
                .isPresent();
        if (!proposalValid) {
            String message = expectedAction == LlmProposalAction.CREATE
                    ? "The source Proposal is no longer an approved CREATE in the active workspace"
                    : "The source Proposal is no longer an approved MERGE in the active workspace";
            throw new WikiPublishException(WikiPublishException.Reason.PROPOSAL_INVALID, message);
        }

        // 4. Persisted ASK evidence 在 commit point 仍 current；與 validator 單一 authority 一致
        // （SOURCE workspace/status/parse/hash、WIKI revision/knowledgeId/canonical bytes）。
        // 非 ASK 為 no-op（空 list），不退化既有語意。
        if (!askEvidenceValidator.validatePersisted(workspaceId, proposalId).isEmpty()) {
            throw new WikiPublishException(WikiPublishException.Reason.PROPOSAL_INVALID,
                    "The source ASK evidence is no longer current in the active workspace");
        }
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
