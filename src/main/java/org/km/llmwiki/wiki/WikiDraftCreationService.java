package org.km.llmwiki.wiki;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * #601：所有草稿建立路徑的單一 atomic authority 邊界（auto／manual／regenerate 共用）。
 *
 * <p>正確性來自 V35 partial unique index（storage 層序列化），本服務只做 deterministic
 * 復原：撞上 single-usable 衝突時沿用競爭者已提交的可用草稿（經 {@link WikiDraftReusePolicy}），
 * 不各建一筆、不猜測。非交易協調器：每個步驟自帶交易邊界；regenerate 的作廢與新建分屬
 * 兩個已提交步驟（作廢先提交釋放名額；新建失敗不回滾已作廢——內容仍可讀，見测试契約）。
 */
@Service
public class WikiDraftCreationService {

    private final WorkspaceService workspaceService;
    private final WikiDraftPersistenceService draftPersistenceService;
    private final WikiDraftRepository draftRepository;
    private final WikiDraftReusePolicy reusePolicy;

    /**
     * In-process creation serialization (#601 layer 1). The deployment contract is
     * single-instance (#417/#418), so one JVM-wide leaf lock deterministically serializes
     * every application creation path (auto／manual／regenerate): the loser always observes
     * the winner's committed row and reuses it instead of racing SQLite snapshots
     * ({@code SQLITE_BUSY_SNAPSHOT} is a stale-snapshot write conflict that busy_timeout
     * does not cover). The V35 partial unique index (layer 2) remains the absolute storage
     * authority — even an out-of-process writer cannot create two usable drafts; it only
     * receives a typed conflict. Reentrant: regenerate holds it across invalidate＋create.
     */
    private final ReentrantLock creationLock = new ReentrantLock();

    public WikiDraftCreationService(WorkspaceService workspaceService,
                                    WikiDraftPersistenceService draftPersistenceService,
                                    WikiDraftRepository draftRepository,
                                    WikiDraftReusePolicy reusePolicy) {
        this.workspaceService = workspaceService;
        this.draftPersistenceService = draftPersistenceService;
        this.draftRepository = draftRepository;
        this.reusePolicy = reusePolicy;
    }

    /** Manual REST create 與 auto-draft 共用的建立入口：成功新建或沿用競爭者草稿。 */
    public CreatedDraft createOrReuse(long proposalId) {
        return createWithLink(proposalId, null);
    }

    /** 攜帶 regenerate link 的建立入口（link 僅為血緣註記，不影響可用性判定）。 */
    public CreatedDraft createWithLink(long proposalId, Long regeneratedFromDraftId) {
        // 锁必须在第一条 SQL 之前：等待锁期间若已取 snapshot，SQLite WAL 会报
        // SQLITE_BUSY_SNAPSHOT（stale snapshot 写冲突，busy_timeout 不覆盖）。
        creationLock.lock();
        try {
            WorkspaceResponse workspace = activeWorkspace();
            try {
                WikiDraftResponse created = draftPersistenceService.createLinked(proposalId, regeneratedFromDraftId);
                return new CreatedDraft(created, false);
            } catch (DataAccessException conflict) {
                StoredWikiDraft usable = reusePolicy.reuseOrThrow(workspace.id(), proposalId, conflict);
                return new CreatedDraft(draftPersistenceService.get(usable.id()), true);
            }
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * Regenerate 編排（自 #570 persistence 搬移，語意變更見 #601）。
     *
     * <p>順序：先作廢舊可用草稿（提交，釋放 single-usable 名額），再經
     * {@link #createWithLink} 建立或沿用。新建若驗證失敗，舊草稿維持 INVALIDATED
     * （內容仍可讀；publish 不受影響因 INVALIDATED 本就不可發布）。
     * 與併發建立競爭時沿用勝者，任何時刻至多一筆可用草稿。
     */
    public CreatedDraft regenerate(long draftId) {
        WorkspaceResponse workspace = activeWorkspace();
        StoredWikiDraft oldDraft = draftPersistenceService.requireUsableForRegeneration(workspace.id(), draftId);
        // 與 createWithLink 持同一把鎖（可重入）：作廢＋新建對其他建立路徑原子可見，
        // 併發建立者要嘛先提交（此處沿用勝者），要嘛排在作廢之後（正常新建）。
        creationLock.lock();
        try {
            if (oldDraft.status() == WikiDraftStatus.DRAFT || oldDraft.status() == WikiDraftStatus.READY) {
                draftRepository.transition(workspace.id(), oldDraft.id(), oldDraft.status(),
                        WikiDraftStatus.INVALIDATED, WikiDraftInvalidationReason.SUPERSEDED_BY_REGENERATION);
            }
            return createWithLink(oldDraft.proposalId(), oldDraft.id());
        } finally {
            creationLock.unlock();
        }
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new);
    }

    /** 建立結果：response＋是否沿用既有可用草稿（auto-draft 回應保真用）。 */
    public record CreatedDraft(WikiDraftResponse response, boolean reused) {
    }
}
