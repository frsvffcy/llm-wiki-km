# OpenViking hierarchical context、progressive loading 與 agent-facing filesystem patterns evaluation

- 評估日期：2026-09-16
- 外部來源：volcengine/OpenViking official repository / official docs（audited 2026-09-16；README 標示 current release `0.3.22`；main branch；official site https://openviking.ai/；docs https://docs.openviking.ai/）
- 補充來源：使用者提供之 OpenViking 專家說明文章（視為 historical observation / design narrative，不作 current contract authority）
- Classification：`TRACK_FULL`
- Current decision：`NO RUNTIME ADOPTION；TRACK AS DESIGN INPUT / DEFERRED CANDIDATES`
- Authority：decision evidence / design input only；不得取代 AGENTS、ADR、production code/tests/CI、GitHub Issue ownership。

## 1. Executive decision

OpenViking 對 `llm-wiki-km` 有長期設計參考價值，但目前沒有證據支持直接導入 runtime 或建立 parallel agent-memory subsystem。

目前 `llm-wiki-km` 的 current authority / product boundary 已明確：

```text
archive/ + vault/ + authoritative content = canonical authority
SQLite = durable operational/control plane
FTS / vector / Graph = derived/rebuildable projections
EvidenceBundle / citation / currentness = application-owned
Ask = ephemeral/read-only
persistent knowledge = Proposal → Draft → Human Review → Publish
MCP = loopback-only read-only adapter
```

OpenViking 值得追蹤的不是「用檔案系統取代 vector RAG」，而是以下 agent-context patterns：

- hierarchical virtual namespace（resources / memories / skills）；
- directory-level L0/L1 semantic sidecars + L2 original detail；
- progressive context loading / token-budget degradation；
- vector recall + rerank + filesystem navigation 的混合 retrieval；
- retrieval trajectory / match-reason observability；
- session → memory extraction / evolution 的可稽核 flow；
- agent-facing `ls/tree/read/find/search` ergonomics。

結論：`OpenViking runtime/framework adoption = NO-GO NOW`；hierarchical navigation、progressive loading、retrieval observability、context-type separation 採 `ADOPT AS FUTURE DESIGN INPUT`；self-evolving durable memory 與 agent-facing write 採 `DEFER / GOVERNANCE-RESTRICTED`。

## 2. 官方資料交叉檢核

### 2.1 專家說明中需要修正的簡化

以下四點以 current official docs 為準，專家文章的舊說法只保留為 historical observation：

#### （1）OpenViking 仍使用 vector retrieval + rerank

專家敘述若被讀成「OpenViking 不用 vector RAG、完全以 filesystem 取代 vector」，屬於過度簡化，應明確修正。

Current official retrieval pipeline（`docs/en/api/06-retrieval.md`、`docs/en/concepts/07-retrieval.md`）仍是：

```text
Query
→ intent analysis（search；find 為純 vector 路徑）
→ L0 vector search（hierarchical / global vector search）
→ L1 rerank（THINKING mode；失敗時 fallback 回 vector scores）
→ result / progressive read
```

並另提供 regex / filename / filesystem navigation。因此正確描述是「用 hierarchical filesystem organization + progressive context loading 改造 flat vector-RAG 的 context management」，不是「完全以 filesystem 取代 vector」。

`search(mode="context")` 另有 server-side assembly face（bounded intent expansion、per-category quotas、token-budget tier filling、cross-turn dedup、optional LLM digest），但仍建構在同一 L0 retrieval 之上，不構成「無 vector」證據。

#### （2）L0/L1 是 directory-level sidecars，不是每 file 三份檔

Current official `Context Layers` 定義：

| Layer | Name | Storage | 語意 |
| --- | --- | --- | --- |
| L0 | Abstract | directory 下 `.abstract.md` | 預設 256 字元；vector retrieval / quick filtering |
| L1 | Overview | directory 下 `.overview.md` | 預設 4000 字元；rerank / navigation |
| L2 | Detail | original files + subdirectories | 完整內容，on-demand loading |

關鍵語意：

- L0/L1 是 **directory-level semantic sidecars**；普通 file 是 L2 detail，file summary 可聚合進父 directory L1（bottom-up semantic processing：file summaries → leaf L1 → leaf L0 → parent）。
- L0/L1 不保證共存；`mkdir()` 可能只先建 L0；caller 不得假設每個 directory 同時有兩份 sidecar。
- Multimodal file 先產生 text summary 再貢獻給所屬 directory L0/L1；不為每個 image/audio/video 建立 per-file L0/L1。
- 不得把每個普通 file 誤寫成各自固定持有 L0/L1/L2 三份檔案。

#### （3）URI / context model 已比早期文章更細

- Current model 有 user-scoped home alias，會展開至 `viking://user/{user_id}/...`；memory 與 skill 路徑、peer scope、ACL/current auth semantics 需依 current docs。
- 不應沿用舊 `viking://user/preferences` 範例當 current contract。
- `hierarchical_retriever.py` 的 root mapping 顯示 `MEMORY → viking://user/.../memories`、`RESOURCE → viking://resources`、`SKILL → .../skills`，並有 peer-collection filter（`X-OpenViking-Actor-Peer` / `actor_peer_id`）；此為 current implementation evidence，不得以舊文章範例覆蓋。

#### （4）Memory type 不只早期五類

專家文章若只列 profile / preferences / entities / events / cases / patterns，應標示為舊 snapshot。

Current built-ins 還包含 identity、soul、trajectories、experiences、tools 等，並支援 policy/registry extension。`search(mode="context")` 的 purpose presets（`chat` / `coding`）與 per-category quotas（events / entities / preferences / experiences / resources / skills）顯示 category 仍在演進；本 evaluation 不凍結一份「完整 memory-type 清單」當 canonical contract。

### 2.2 Benchmark interpretation

Current README（OpenViking 0.3.22）公開的主要 evidence 是 LoCoMo + tau2-bench：

- LoCoMo：OpenClaw / Hermes / Claude Code with OpenViking 約落在 80–83% accuracy；官方同時宣稱 input-token reduction 34.3–91.0% 及 query latency reduction 58.45–66.10%。
- tau2-bench：retail +6.87pp、airline +11.87pp task success。
- Reproduction scripts 在 `benchmark/`；完整 setup 指向官方 benchmark report。

使用者提供文章中的早期 OpenClaw 24.6M→4.3M token、35.65%→52.08% 等數字可留作 historical observation，但不得當 `llm-wiki-km` own benchmark 或 current OpenViking canonical benchmark。任何 future adoption 若要引用數字，必須重跑 own-corpus / production-equivalent benchmark，不得直接移植外部百分點。

### 2.3 License boundary

- Main project current license 是 AGPLv3。
- 官方 README 明確列 `crates/ov_cli` 與 `examples` 為 Apache-2.0 exceptions。
- SDK 若無個別 permissive LICENSE，不得自行推論「純 SDK 一定 Apache / 可閉源整合」。
- 本 evaluation 只記錄 license boundary，不提供法律意見；若 future 真要觸碰 OpenViking code（含 SDK、CLI、examples 之外），需另開獨立 license / architecture decision，不得以本 evaluation 當授權依據。

結論：`導入 AGPL server code 進 current Java runtime = NO-GO NOW`，且沒有獨立 license decision 前不得重新評估。

## 3. ADOPT AS DESIGN INPUT

### A. Progressive context loading / degradation

可借鏡 L0/L1/L2 的核心思想，但不要複製 OpenViking schema：

```text
compact navigation / summary
→ bounded overview
→ exact authoritative detail on demand
```

對本專案 future agent-facing context 或 MCP navigation，優先讓 agent 先看 bounded structural/summary projection，再依需求進 source chunk / canonical detail；exact claim/citation 仍必須回 authoritative source revalidation。

具體約束：

- summary/projection 不得升格為 citation authority；
- token-budget degradation 不得 silent truncate authoritative content；
- 任何 policy 輸出若走向 `EXTRACTIVE`，仍受既有 `AnswerContextCompactionPolicy` / benchmark / regression gate 治理；
- provider-dependent 行為需沿 #323 / #310 disclosure 語意揭露。

### B. Hierarchical namespace for agent ergonomics

`viking://` 顯示 filesystem-like namespace 對 agent navigation 很自然。Future 若 `llm-wiki-km` 要擴充 agent-facing read interface，可評估以 application-owned virtual namespace 暴露：

```text
workspace / sources / wiki / retrieval / quality / skills(?)
```

但它只能是 **navigation/read projection**，不得讓 URI path 變成 canonical identity authority，也不得直接暴露 raw host filesystem path。

具體約束：

- canonical identity 仍是既有 knowledgeId / source-chunk identity / citation identity；
- virtual namespace 不得建立第二套 citation identity；
- 不得把 host absolute path、RID、local path 經 namespace 洩漏；
- workspace isolation 仍由 server-side 強制，不得靠 path convention 自律。

### C. Retrieval trajectory observability

OpenViking 將 retrieval path / reason 做成可觀測 evidence（`MatchedContext.match_reason`、`query_plan`、`query_results`、directory recursion trajectory），值得和本專案既有 Retrieval Inspector 對照。Future 若 current usage 證明 inspector 仍難以回答「為何選到這個 evidence」，可評估增加 bounded trajectory：

```text
query
→ modality/candidate source
→ filter/currentness rejection
→ rerank/fusion reason
→ admitted Evidence identity
```

不得曝光 secret、raw provider body、RID、local path 或不可公開 internal score detail。Trajectory 是 diagnostic projection，不是 ranking authority；retrieval 側 terminal/handoff guard 仍權威。

### D. Context type separation

Resource / Memory / Skill 的分類有設計價值：不要把 user preference、knowledge evidence、tool procedure 全丟到同一 retrieval pool。

對 `llm-wiki-km` 的 future agent layer，至少維持：

```text
knowledge evidence ≠ user/agent memory ≠ skill/procedure
```

不同 context type 必須有不同 authority / write / retention / citation semantics。特別是 memory 不得混入 canonical Wiki evidence pool；skill/procedure 不得因共享 retrieval 而自動取得 canonical write capability。

## 4. DEFER / FUTURE CANDIDATE

### E. Agent memory / self-evolution

OpenViking session commit 會自動做 LLM extraction、candidate compare、merge/delete/create，再寫入 long-term memory；這 **不能直接移植** 到 current `llm-wiki-km`。

本專案 persistent knowledge 仍必須：

```text
Proposal → Draft → Human Review → Publish
```

若未來真的建立 agent/user memory plane，也必須先定義：

- memory 不是 canonical Wiki evidence；
- auto-extraction 是否只可產生 Proposal / candidate，而非直接寫 durable canonical state；
- source provenance / revision / conflict / expiry / correction；
- per-memory-type authority 與 retention；
- A2 / Human Review 與 MCP write boundary；
- deletion / merge 不可繞過 current no-physical-delete / audit posture。

在沒有真實 long-running-agent pain evidence 前維持 `DEFER`。

### F. Agent-facing filesystem/MCP write

Current MCP 維持 read-only。OpenViking MCP 提供 remember/write/edit/delete 等 mutation surface，僅可作 future design input，不得因外部工具成熟就升格 roadmap。

若 future MCP/agent write 真的立項，需和既有 evaluation lineage 合併：

- optimistic version / expected hash；
- no physical delete by default；
- auditable / reversible mutation；
- Action Risk A2；
- Proposal / Human Review authority；
- application-owned path / identity validation。

## 5. NO-GO NOW

- 以 OpenViking 取代 current FTS/vector/Graph Hybrid RAG。
- 以 OpenViking memory 作 Wiki/citation canonical authority。
- 將 `viking://` 路徑語意直接植入 production domain model。
- 為 agent ergonomics 直接開放 host filesystem。
- 導入 AGPL server code 進 current Java runtime，而沒有獨立 license / architecture decision。
- 自動 session memory write 繞過 Proposal→Review→Publish。

## 6. Future adoption triggers

只有至少一項 current evidence 成立才開 implementation/evaluation Story：

1. long-running agent / cross-session workflow 已成實際產品需求，且現有 stateless Ask + external ChatGPT Memory 無法滿足；
2. current Evidence/Context packing 持續出現可重現 token-noise / over-fetch / context-budget 問題；
3. Retrieval Inspector 仍無法回答實際 debugging 所需的 candidate/admission trajectory；
4. current MCP read-only surface 對 agent navigation 有可重現 tool-use friction；
5. 明確需要區分 user memory / agent experience / canonical knowledge 且現有 domain model 無法安全承載。

觸發後第一張工作應是 benchmark / contract evaluation，不是直接整合 OpenViking。

## 7. 最終判定

| 項目 | 判定 |
| --- | --- |
| OpenViking runtime/framework adoption | `NO-GO NOW` |
| hierarchical agent-context namespace | `DEFER / HIGH-VALUE DESIGN INPUT` |
| progressive context loading | `ADOPT AS FUTURE CONTEXT PATTERN` |
| retrieval trajectory observability | `ADOPT AS FUTURE INSPECTOR INPUT` |
| resource / memory / skill separation | `ADOPT AS ARCHITECTURE PRINCIPLE` |
| self-evolving durable memory | `DEFER / GOVERNANCE-RESTRICTED` |
| MCP / agent write | `NO CURRENT ADOPTION` |

## 8. 對 current roadmap 的影響

本 evaluation 是 docs/evaluation work，**不得阻塞或插隊**目前 v0.1.0 Release Readiness（#432 → #435 → #428，已完成）。可平行完成 docs-only evaluation，但不得改 release scope 或以 OpenViking candidate 取代 release blocker。

不修改 runtime/default、不新增 dependency、不建立 speculative production integration Issue。

Refs #405、#327、#360、#428、#432、#435。
