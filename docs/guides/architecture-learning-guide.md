# Architecture learning guide（current）

> 狀態：`CURRENT`（learning source；對應 latest `main`）。
> 來源：本 Markdown 為唯一學習來源；不另維護並行的 HTML authority 副本，避免雙軌 drift（Refs #410 challenge case 6）。
>
> ```text
> Learning Aid
> ≠ Executable Contract
> ≠ Architecture Decision Authority
> ```
>
> Executable authority：schema → Flyway migrations；
> API → latest `main` Controllers＋contract tests；decisions → `docs/adr/`；
> roadmap → GitHub Issues＋`AGENTS.md`。本指南只解釋、不定義 runtime。
> 歷史設計見 `../architecture/legacy/`（`HISTORICAL`，不可作 current contract）；
> 系統現況導航見 `../architecture/README.md`。

## 0. 如何讀這份指南

1. 先把 AI 放到旁邊：Controller、Service、Repository、transaction、migration、filesystem I/O
   都是標準 Spring Boot 工程材料。LLM 是「不可信但有創造力的外部計算者」，不是資料庫。
2. 每次看到能力名詞，先問三件事：authority 在哪裡、projection 可否重建、失敗時降級為什麼。
3. 遇到數字（migration 區間、endpoint、policy version）以 code／Flyway／tests 為準；
   本指南的版本號只為學習錨點，不作 contract。
4. 歷史文件（`legacy/`）只回答「我們以前怎麼想」；不要把它的 table／endpoint／roadmap 當成現在的系統。

## 1. 一句話定位與分層

系統是「由 Java 控制流程與安全邊界、由 LLM 提出內容建議、由人類核准、最後把 Markdown 發佈到 vault」
的本機知識管理系統。分層核心為 canonical authority／durable operational state／rebuildable projection
三層（`archive/`＋`vault/` 為 durable canonical authority；SQLite 為 operational／control plane；
chunks／FTS／embedding／graph 皆為可重建 projection）。責任鏈見 `../architecture/system-overview.md`：

```text
L0 Canonical files（archive/ + vault/ + authoritative metadata/content）
→ L1 Durable operational state（SQLite + Flyway + jOOQ）
→ L2 Rebuildable projections（chunks、FTS、embedding、graph）
→ L3 Retrieval candidates（lexical / vector / graph → fusion → rerank）
→ L4 Evidence（admission 重驗後才有 citation 資格）
→ L5 Grounded answer（ephemeral，citation-validated）
```

三道不變式：canonical 不被 projection 反寫；projection 可由 L0／L1＋generation 重建；
用於答案的 candidate 必須先成為 Evidence。失敗一律走 typed diagnostics，不 silent backfill。

## 2. SQLite＋Flyway＋jOOQ authority

- 單一 canonical metadata DB（預設 `data/knowledge.db`，可由 `KNOWLEDGE_DB_PATH` 覆寫）；
  每連線 `foreign_keys=ON、journal_mode=WAL、synchronous=NORMAL、busy_timeout>0`（預設 5000）。
- Flyway 為唯一 schema authority：`src/main/resources/db/migration/`（SQL）＋
  `src/main/java/db/migration/`（Java，至少含 V3）構成連續 chain（以 `main` 目錄為準；
  本指南不硬編固定區間，舊「V1～V17」／「V1～V29」／「V1～V33」引用皆已停用）。
  近期 lineage：chunking policy version、Ask／repair ingress 與 retry、normalization policy
  version（見 `../architecture/schema.md`）。已發布 migration 不得修改；新 schema 只以新 `V{n}` 交付。
- jOOQ `Tables`／`Records` 為 build-time generated（`-Pfull` 重生成；禁 DAO／POJO），留在 persistence 層，
  不得成為 domain／REST contract。Production runtime 只經 `DSLContext`＋repository 邊界；
  不新增 `JdbcClient`／`JdbcTemplate` inline SQL。
- 新增 persistent application table 必須同步檢查 `IsolatedIntegrationTest` reset hook 與
  `DatabaseCleanupPolicy` completeness guard。
- 相關：`../architecture/schema.md`、ADR 0008（feasibility spike 方法）。

## 3. FTS5＋cjk-bigram-v1

- FTS5 為 lexical 基線；CJK 以 `cjk-bigram-v1` deterministic lexical projection 處理（production 唯一 query 側轉換；
  見 §13）。
- FTS rebuild admission 為 atomic contract（overlapping corpus 409、job insert＋ownership claim 同一 transaction、
  late worker 只能完成自己擁有的 state）。
- 相關：ADR 0001、`../architecture/capability-map.md`（`search/` owner）。

## 4. Provider-neutral embedding＋sqlite-vec

- Embedding 經 `EmbeddingClient` interface；vector candidate search 經 `KnowledgeVectorRepository`；
  核心服務不 import provider 實作；native path／loading 留在 SQLite adapter 後方，不暴露給 Browser／REST／Ask。
- Embedding projection 為 generation-aware rebuildable projection：`target_generation`／`applied_generation`＋
  immutable operation ledger；snapshot token 為 SHA-256 boundary；僅完整 proof 授予 READY；
  STALE／QUEUED／REBUILDING／PARTIAL／FAILED 皆非 serving state（僅 READY 可 serve）。
- Semantic serving 另需 backend capability、workspace＋corpus READY、query-time metadata／freshness／authority 重驗；
  `SEMANTIC_*` 未就緒 fail closed，`HYBRID_VECTOR` 可標 degraded lexical fallback（typed diagnostics）。
- 相關：ADR 0003、ADR 0004、ADR 0006。

## 5. ArcadeDB derived Graph projection

- Graph 為 optional／degradable derived modality；ArcadeDB 為 replaceable embedded projection，
  可刪除重建；SQLite 持續是 operational／control plane（workspace-scoped generations、lifecycle／readiness、
  operation ownership、compare-and-set recovery），不得被取代或成為 migration target。
- SQLite 只持 `graph_projection_lifecycle` control proof；內容在 ArcadeDB derived backend；
  READY 需 SQLite lifecycle＋backend proof＋canonical fingerprint 三方驗證；canonical drift 在 readiness check 持久化降級。
- 部署基線為 embedded、local-first、single-process；second writer／server／cluster／HA 不支援；
  檔案鎖＋application／session ownership fail closed。
- 明確更正舊指南：vector／graph 不是 future projection；ArcadeDB 不是 SQLite migration；
  Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 仍為 future adapter candidates（需 adoption gate）。
- 相關：ADR 0007～0010、`../architecture/schema.md`。

## 6. Bounded Graph Retrieval／GraphRAG

- Traversal 為 provider-neutral bounded outgoing BFS（seeds≤16、depth≤4、per-node≤32、per-hop≤128、
  visited nodes≤512／edges≤1024、candidates≤200）；query 帶 exact expected snapshot；
  generation／version／fingerprint／token／workspace drift 一律 fail closed。
- Relation profile `graph-projection-v2` 只承認 `CONTAINS`、`LINKS_TO`、`TAGGED_WITH`、`DERIVED_FROM` evidence；
  普通文字不建 `MENTIONS`，`RELATED_TO` 維持 DEFER；v1→v2 只允許 full rebuild。
- Graph candidate → Evidence 經 `GraphEvidenceAdmissionService`：admission-time snapshot revalidation＋
  per-candidate workspace／authority／provenance／freshness／eligibility 重驗＋hard admission budget；
  未通過者排除或標不可用，不得直接成為 citation。
- `HYBRID_GRAPH` 經 application-owned deterministic fusion（identity-level RRF，無 raw-score blending；
  global＋per-modality budgets；terminal publication guard 重驗）＋Ask handoff currentness guard（fresh window 重查 snapshot＋authority；drop 永不 silent backfill）。
- Graph／backend outage 維持 lexical＋vector baseline（typed diagnostics）；integrity／correctness violation 則 fail closed，
  不偽裝成 degradation。
- 相關：ADR 0011、ADR 0012、`../architecture/system-overview.md`。

## 7. Evidence admission／authority／provenance／freshness／workspace currentness

- Citation 身份固定為 `WIKI:<knowledgeId>`／`SOURCE_CHUNK:<id>`；locator 不參與 identity／ranking／authority。
- 所有 modality candidate 成為 Evidence 前須經 workspace scope、authority、provenance、freshness、eligibility 重驗；
  stale／foreign／deleted 不得進入；`NOT_CURRENT`＋typed reason 時不暴露內容。
- Structure-preserving ingestion：typed blocks＋versioned `ChunkingPolicy`
 （預設 `chunk-policy-v1-current`；`source_chunk.chunk_policy_version`；policy 變更需 re-extraction）。
  Parsed structure 與 chunks 為 derived projection，永不成為 citation authority。
- Versioned `NormalizationPolicy`（#412；與 chunking 正交）：production default
  `normalization-policy-v2-selected-cf-strip`（僅 strip U+00AD／U+200B／U+2060／非開頭 U+FEFF；
  ZWJ／ZWNJ／BiDi 永不 strip），rollback target `normalization-policy-v1-current`；
  `source_chunk`／`document_extracted_content` 以 `normalization_policy_version` 記 lineage，
  upgrade／rollback 皆需 explicit re-extraction（經既有 FTS／embedding／graph 重建路徑）。
- Bounded extraction（#287）：input／output／metadata／structure 上限為 typed fail-closed contract，不得退化。

## 8. Hybrid fusion／reranking

- Fusion 為 identity-level reciprocal rank fusion（canonical identity dedupe；hard budgets；typed per-modality degradation；
  terminal guard 重驗後才離開 fusion boundary）。
- Second-stage rerank（ADR 0014）：versioned `SecondStageRerankPolicy`
 （`rerank-policy-v1-noop` rollback target／`rerank-policy-v1-exact-anchor` adopted default；
  `km.rag.rerank.policy-version` 選擇，unknown／duplicate fail-fast）；只 reorder 已 qualification 的 canonical evidence
 （identity set／citation／hash／provenance 不變；`RerankStatus`／`RerankNoOpReason` typed no-op）；
  policy defect 時 deterministic fallback 回 baseline order。套用點在 qualification 後、packing 前。
- Answer Context Projection（ADR 0013）：`EvidenceContextProjector` 為唯一 production packing path；
  預設 `context-policy-v1-current`（baseline 語意）；`EXTRACTIVE` 需 applicability 判定器＋benchmark＋regression gate。
- Ask context observability（#310）：typed diagnostics 沿 additive safe DTO；`code points ≠ provider tokens`。

## 9. Grounded Ask＋citation validation

- Contract 為 `grounded-answer@v2`（answer text＋application-issued citation ids＋insufficient-evidence flag；
  unknown fields 拒收；response 不持久化；單一 production caller）。
- Ask 為 stateless grounded Ask：每次獨立 request；Browser 不保留問答歷史；answer 只帶 citation provenance，
  不開 local files 或 provider endpoints。
- Provider／model identity 由 adapter 從 transport envelope／configured model 建立；model-generated metadata 不可信。
  Answer provider 預設 disabled；未配置顯示 safe `尚未設定回答服務`；credential 只經 backend 環境變數，
  Browser 永不持有。
- 相關：ADR 0002、`../architecture/api.md`。

## 10. Provider egress transparency

- Configuration disclosure（#323）：`ProviderEndpointSecurityPolicy.classify`
 （LOCAL_LOOPBACK／REMOTE_SECURE／REMOTE_INSECURE_OPT_IN／DISABLED／UNAVAILABLE_OR_INVALID）＋
  `GET /api/v1/system/ai-provider-egress`（allowlisted metadata＋data-category disclosure；
  無 key／raw endpoint／path／RID／raw exception）。
- Execution fact（#310 `ProviderUsageStatus`）：本次是否實際呼叫 provider，與 configuration disclosure 分開呈現；
  缺失 counter 標 `UNAVAILABLE`，不偽裝 `AVAILABLE`。
- Browser indicator 在 Ask 輸入區附近以 safe text 呈現（insecure opt-in 醒目、disabled 不偽裝本機、
  disclosure 不可得時隱藏且不擋 Ask、submit 後 refresh 防 stale）。

## 11. MCP read-only capability

- `POST /api/mcp` 為 read-only-first、loopback-only MCP Streamable HTTP adapter；
  current `2026-07-28` stateless＋bounded legacy `2025-06-18`；GET／DELETE 明確 405。
- 五個唯讀 tools 經 shared application boundary 委派（Ask 經 `AskApplicationService`；
  Inspector 經 `RetrievalInspectionMapper`＋service）；無第二套 retrieval／ask pipeline；
  無 write tools／remote bind／agent loop。
- Tool input 為單一 executable contract（strict 無 coercion；bounds／enum／`additionalProperties:false`；
  integer 採 mathematical-integer 語意）；validation failure 為 protocol-level `-32602`，
  genuine 執行失敗為 tool-level `isError`。
- 相關：`../development/issue-330-mcp-transport-compatibility.md`。

## 12. Retrieval Inspector／Source Locator

- Inspector（`GET /api/v1/retrieval/inspect`＋Browser 面板）：同一 production path 的 optional collector 觀察
  （per-modality candidates＋modality-local ordinals、fusion policy version＋fused order、admission＋typed rejection、
  degradation、final evidence order＝Ask handoff）。Read-only；不呼叫 Answer provider；無 raw score／RID／token／
  fingerprint／path；無 ranking sliders；不新增第八種 ranking 語意。
- Locator（`GET /api/v1/source-chunks/{id}/locator`＋inline citation preview＋`#/inspect` hand-off）：
  active-workspace scoped；metadata 依實際精度漸進呈現（document／chunk／page／section／heading；缺席不偽造）；
  bounded preview 以 textContent 安全渲染（零 innerHTML）；not-current／stale／not-found 為 typed states。
- 相關：#292、#293、#375、#381。

## 13. Query transformation seam（default 不得誤寫）

- Lineage：#390 evaluation（`CONDITIONAL GO`；pool 無整體增益，僅 `property-token` wording-mismatch 經 protected rewrite 恢復）→
  #401 production seam（PR #407；versioned registry＋bounded candidate）→ #408 release decision
  （`CONDITIONAL GO / KEEP DISABLED`；live evidence 缺席，不得啟用）。
- Current default：`query-transform-disabled-v1`（production default＋rollback target；不呼叫 provider、不增加 retrieval input）。
  Candidate：`query-transform-single-rewrite-v1`（至多一次 rewrite＋一次額外 retrieval；fan-out ≤2；original 恆為 ordinal 1）。
- Applicability（enabled policy 僅此 shape）：`HYBRID`／`FUSED`＋`cjk-bigram-v1` 後 ≥3 terms 且含受控填充詞＋
  original lexical `EMPTY`＋original overall evidence 非空；其餘 typed no-op 不呼叫 provider。
- Exact-token：protected tokens（property／錯誤碼／Error／Exception class／package-like identifier）逐字保留；
  遺失即 `FALLBACK_EXACT_TOKEN_LOSS`，不得第二次 retrieval。Bounds：response ≤16384 code points；
  rewrite ≤256 code points 且 ≤64 terms；禁控制字元；NFC duplicate→`NO_OP_DUPLICATE`。
- 本指南不改 #408 decision；任何 default 切換需 live-provider controlled measurement＋重跑
  #390／#272／#280／#316（＋條件式 #308）＋provider-free 比較（另開 Issue）。
- 相關：`../development/issue-390-query-transformation-evaluation.md`、
  `../development/issue-401-query-transformation-production-adoption.md`、
  `../development/issue-408-query-transformation-release-decision.md`。

## 14. Proposal → Draft → Human Review → Publish
- 唯一合法 durable knowledge ingress：Proposal（PENDING／ACCEPTED／REJECTED／EDITED／APPLIED；
  action CREATE／MERGE／LINK_ONLY／IGNORE／REVIEW）→ Draft → Human Review → explicit Publish。
- Review 工作台按 backend `allowedTransitions` 驅動；核准不自動 publish；publish 為明確人類動作
  （double-submit guard＋typed outcome＋失敗清除先前成功顯示＋發布後重讀 authoritative state）。
- Ask → Proposal（#374）：獨立 mutation command；citation 逐項驗證（unknown／stale／foreign→422 fail-closed）；
  per-workspace dedup（重複 200＋duplicate=true）；provenance 最小持久化（無 raw payload／secrets）。
- Governed repair（#384）：mutation 當下重驗 workspace／finding currentness／eligibility／lineage；
  eligibility 唯一 authority 為 backend（v1 僅 `CANONICAL_CONTENT_INVALID`＋resolvable governed lineage＋target 可讀）；
  finding 不是 authorization token，不解鎖 MCP write／auto-fix／auto-publish。
- Vault Lint（#379）為 deterministic read-only 健康掃描（broken link／orphan／content validation／dangling provenance；
  typed finding＋deterministic ordering）；Quality 視圖（#383）為唯讀 triage（零 mutation，無 innerHTML）。

## 15. Owner security 與 remote deployment trust boundary

- Single-user owner boundary（#417；`web/security/`）：預設關閉（local-only localhost
  trust）；開啟後 `/api/v1` 需 owner session。Session 為 in-memory opaque token
 （HttpOnly cookie 給 Browser ambient credential＋in-memory Bearer 並存；rotation／logout／
  restart 登出同一 authority）。`Host`／`Origin` allowlist＋cookie-mutation 需 allowlisted
  Origin＋login／mutation rate limit；`Forwarded`／`X-Forwarded-*` 預設不可信。
- Credential hardening（#423）：versioned salted adaptive KDF
 （`pbkdf2-sha256$v1`，PBKDF2-HMAC-SHA256，per-verifier salt＋cost metadata；
  以 `OwnerPasswordVerifierTool` 產生，plaintext 不進 CLI／history／Git）；
  legacy unsalted hash 僅 LOCAL_ONLY migration aid，remote 拒收。
- Deployment profile（#418；ingress contract #422；`system/`＋`deploy/`）：
  raw backend 永遠 loopback-only；`LOCAL_ONLY` SUPPORTED／CURRENT；
  `PRIVATE_INGRESS` 需 canonical browser origin＋allowlists＋forwarder＋cookie transport
  四方對齊才 SUPPORTED（`GET /api/v1/system/deployment` 唯讀投影；否則 `NOT_READY`）；
  public HTTPS 永為 CANDIDATE；direct raw bind REJECT。SSH 無 listener 情境用
  `LOCAL_ONLY`＋owner auth。Auth 只做 admission，不碰 domain／publish／repair／Evidence authority；
  MCP 維持 loopback read-only。
- 相關：`../architecture/system-overview.md`（trust boundary 節）、`../architecture/api.md`、
  `../architecture/capability-map.md`、`../architecture/use-cases.md`、
  `../development/issue-418-remote-deployment-operations.md`（§12）、
  `../development/issue-423-owner-credential-kdf.md`。

## 16. Current／Historical／Proposed 文件治理

- 三態只描述文件時間語意：`CURRENT`（latest `main` 可執行）／`HISTORICAL`（曾有效或早期設計）／
  `PROPOSED`／`CONDITIONAL`（未批准或條件式候選）。L1～L5 只描述 Issue 複雜度，不綁 model／effort。
- Local 長文件（`.ai_llm_wiki_km/`）為 local-only 非 executable authority；Git 內歷史只以
  `architecture/legacy/` 凍結快照與 `evaluations/` dated review 呈現，不把 private 目錄整批納入 Git。
- Evaluation（`evaluations/README.md`）：`TRACK_FULL`／`LINEAGE_ONLY`／`SNAPSHOT_ONLY`；
  `GO` 不等於自動 adoption；`DEFER` 需 trigger 才重評。
- 本指南若與 Flyway／Controllers／tests／ADR／Issues 衝突，以後者為準；發現衝突請開 Issue，不靜默改寫任一邊。

## 17. 建議閱讀順序（對照傳統 Spring Boot）

1. `InboxController → ExtractedContentService → SourceChunkRepository`（先看 ingestion，不先看 prompt）。
2. Analysis job（`DocumentAnalysisController`＋`processing/`）→ proposal／evidence contract。
3. Review→Draft→Publish（`wiki/`）→ FTS／embedding／graph projection lifecycle。
4. Ask（`ai/ask/`＋`rag/`）：retrieve→rerank→projection→answer；再看 Inspector／locator 觀察同一路徑。
5. Egress／MCP／Quality／repair：只看 adapter 邊界與 disclosure，不進 provider 實作。
6. 需要決策理由時才進 `adr/`；需要驗證命令時進 `development/testing.md`。

Refs #410、#424。相關：#306、#405、#408、#393、#412、#417、#418、#422、#423、ADR 0001～0014。
