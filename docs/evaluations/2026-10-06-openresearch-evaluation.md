# 外部產品評估：OpenResearch（alphaXiv/OpenResearch）（2026-10-06）

* **評估日期**：2026-10-06
* **評估方式**：唯讀外部調查（GitHub README、`SKILL.md`、`SYSTEM_PROMPT.md`、`agent-skills/orx-evidence`／`orx-lit-review`）；對照本專案 `ai/ask`（grounded Ask、citation 驗證）、`rag/`（EvidenceBundle currentness、`revalidateForHandoff`、`CandidateAuthorityRevalidator`）、`processing/`（processing_log）、§1.4 LLM governance boundary、§1.5 無 agent loop 邊界與 loopback read-only MCP adapter（#327～#341）
* **評估對象**：https://github.com/alphaXiv/OpenResearch ——「The local-first harness & workspace for research agents」（把 coding agents 轉成研究 agents）；Rust、MIT、6,637 stars、2026-06-07 建立；**audited revision：main `951700eff4`（2026-10-06），latest release `v0.2.16`**（迭代速度：日級 commit、README 當日仍在改）
* **結論**：🟡 **參考價值集中在「agent 產出訊息的證據紀律」與「本專案作為本機研究 agent 知識後端」兩個層面；harness／compute／multi-agent orchestration 層全部不採用（牴觸無 agent loop 不變條件）**。OpenResearch 與本專案共享 local-first、evidence-grounded 的哲學，但它把自主迴圈放在 agent 側，本專案把迴圈關在人類治理側——兩者是互補而非同類。最有價值的是：其「status alone is not evidence」的 run-evidence 契約（直接強化既有 Ask citation/currentness 的論述與一個 additive UX 缺口）、immutable experiment node＋固定 run contract 的可比性設計（與本專案 versioned policies、evidence identity 不可變同構，屬反向確認）、以及經既有 loopback read-only MCP adapter 成為外部研究 agent 知識來源的整合想像（Proposed、trigger-gated）。

---

## 1. 定位對照

| 向度 | OpenResearch | llm-wiki-km |
| --- | --- | --- |
| 本質 | 研究 agent 的 harness＋workspace（驅動 Claude Code／Codex 等做文獻→假設→實驗→產出） | Local-first 個人知識管理單體（治理式知識庫＋grounded Ask） |
| 迴圈位置 | agent 自主迴圈（假設→實驗→從結果學習→下一輪） | **無 agent loop**（§1.5）；Ask 為 stateless ephemeral；寫入走 Proposal → Human Review → Publish |
| 形態 | Rust 桌面 app＋`orx` CLI；每個 agent session 一個專屬 git worktree | Spring Boot 單體、localhost、SQLite；Browser＋MCP 唯二介面 |
| 證據通道 | run log 為證據通道（「Run logs are the evidence channel」）；local SQL 記錄每個實驗；chat 內強制 file/run tag 引用 | EvidenceBundle currentness 重驗＋citation identity＋Source Chunk locator＋processing_log |
| 結果可比性 | 固定 run command＋env 契約（「identical on every node」）；只變 committed code/config；節點一經回答即凍結 | versioned policies（chunk／normalization／rerank／context）＋pinned corpus＋per-case regression gate（#316/#390/#308） |
| 記憶 | 實驗樹（immutable node＋branch child 記新假設）＋artifacts 全部留本機 | vault（canonical Wiki，hash 驗證）＋Ask→Proposal provenance 最小持久化（#374） |
| 外部服務 | alphaXiv／OpenAlex／bioRxiv／PubMed connectors；openresearch.sh 帳號＋managed compute（opt-out telemetry） | 本機優先；provider egress 分類與揭露（#323）；MCP loopback-only read-only |
| 治理 | Git 為真相（branch／freeze／worktree 隔離） | Proposal → Draft → Human Review → explicit Publish；#282 diagnostic redaction boundary |

## 2. 可參考之處（依價值排序）

### 2.1 「Status alone is not evidence」＋強制引用契約（最高價值：論述借鏡＋一個 additive UX 缺口，零 backend 變更）

`orx-evidence` 明文：**「Never infer a result from run status or memory. Before accepting or reporting a run-derived claim, confirm that: the log identifies the variant and effective configuration; the final metric and compact summary are present; … the cited file lines actually contain the supporting output.」**以及「**Truncated output is not evidence of absence.**」；`SYSTEM_PROMPT.md` 強制 chat 中每個實質主張後面立刻接可點擊引用（`<file path>`／`<run id>` tag），並要求**「Clearly label an inference instead of presenting it as an observation.」**、要求「echo the configuration the run actually used so the log identifies the variant」。

對本專案：這與 Ask 的 citation 逐項驗證、EvidenceBundle currentness guard、以及 #673 加入的「不代表先前回答一定使用這些來源」文案完全同構——且本專案的 enforcement 更強（後端 typed 驗證 vs. 對方的 prompt 層契約；其「prompt 契約 ≠ 執行證據」正是本專案 §1.4「model-generated metadata 一律不可信」的同義句）。**真正可借的是一個尚未覆蓋的 UX 缺口**：本專案目前區分 grounded answer／`INSUFFICIENT_EVIDENCE`／typed failure，但對「answer 內哪些敘述是 observation（有 citation 支撐）vs. 推論（無直接引用）」沒有呈現規範。OpenResearch 的「推論必須標示為推論」可作為未來 Browser answer rendering 的 additive candidate（Proposed、小、零 backend 變更）。**Trigger**：#678 dogfood 出現「看不出哪些話是模型推測」的 B 類 finding 才立項。

### 2.2 Immutable evidence node＋固定 run contract（反向確認＋一個小借鏡）

SKILL.md cardinal rules：「**Never edit a node once a run has answered it** … a disappointing result is still a result」；「The run command *and* the environment are a fixed contract — identical on every node」（唯一可變的是 committed code/config）；「Vary code, not knobs-in-the-command」。

對本專案：
- **反向確認（不用做事）**：`RerankResult` ordered view 對已 qualification evidence「identity set／citation／hash／provenance 不可變」、`revalidateForHandoff`「只刪不加不改位」、published Wiki revision＋content hash 樂觀鎖——本專案已在 production 強制更強的版本；versioned policies＋pinned corpus 即本專案的「fixed run contract」（#316 evaluation winner 與 production 逐 query parity、#390 兩次 pass 可重現，正是同一紀律）。
- **小借鏡（暫不動作）**：#679 friction ledger 目前是平面清單（session／taskFamily／category／reproducedAgain…）。若 dogfood finding 量大，可借「節點凍結、後續觀察記 child」的 lineage 組織法；屬 ledger 書寫慣例，非程式碼工作。

### 2.3 本專案作為本機研究 agent 的知識後端（最大的整合想像；Proposed、trigger-gated）

OpenResearch 的 agents 可接 alphaXiv／bioRxiv／PubMed 等公共文獻來源，但**沒有「個人長期知識庫」的 grounding**——這正是 llm-wiki-km 的核心資產。本專案既有 **loopback-only、read-first MCP adapter（#327～#341：五個唯讀 tools、single executable input contract、無 write tools）**，意味著本機 OpenResearch／Claude Code 等 agent 可以在不違反本專案「無 agent loop」不變條件的前提下（自主迴圈在 agent 側，本專案始終是唯讀知識提供者，如 Ask 之於 MCP），把使用者的 vault／archive 作為假設形成與文獻檢視之外的第三個 grounding 來源。

**列 Proposed**；與 `2026-09-22-mcp-task-discoverability-evaluation.md` 同池管理。**立項 trigger**：#678 dogfood 出現「研究／寫作流程實際需要查詢個人知識庫」的 B 類 finding；屆時走 MCP tool capability 評估（不新增 write tools、不鬆綁 loopback、不改 Ask ephemeral 語意）。

### 2.4 Skill 打包模式：cardinal rules＋聚焦 modules＋anti-pattern callout（寫作方法借鏡）

OpenResearch 的文件結構：頂層只放「不遵守會 silently invalidates results 的 cardinal rules＋command quick-reference」，細節拆成按需載入的 modules（`orx skill <name>`），並明寫 anti-pattern：「If you're ever tempted to change the command, pass an env var, or pile another node onto the root instead of branching a child … **stop. That's the anti-pattern, not a shortcut.**」；playbook 與 skills 分離（durable context vs. task procedure）。

對本專案：`AGENTS.md` §7 漸進揭露＋「本文件是穩定操作導覽、深層細節以索引取得」已同構（反向確認）。可借的小點：docs/development 的操作型文件（`testing.md`、`github-delivery-governance.md` 等）中**反覆被誤用／誤判的規則**（如「PR Gate 全綠才可合併、任一 skipped 不得綠燈」、「唯讀不等於無資料外傳」），可補「anti-pattern」段落，把歷史上實際踩過的錯（如 #360 的 failure-layer 誤判案例）寫成「如果你正想做 X——停下，那是反模式」。純文字工作、無成本，可在下次觸及該文件時順手做。

### 2.5 文獻 grounding connectors（DEFER：egress／ingest policy 議題）

`orx-lit-review` 的 connector-based retrieval（structured JSON、date／ranking controls、figures-first 解釋、「文獻檢視優先於 web search」）是好的 UX 參照。但把外部文獻抓取引入本專案屬新的 egress／ingest policy 議題（#323 分類延伸、#287 bounded extraction 適用性、外部來源與本機 inbox 定位的張力）。**DEFER**——除非 dogfood 出現「需要把外部論文收進知識庫」的 reproducible pain。

## 3. 不採用之處

| OpenResearch 慣例 | 本專案 invariant / 定位 | 結論 |
| --- | --- | --- |
| Agent 自主研究迴圈（hypothesis→experiment→learn→next round） | §1.5 無 agent loop；Ask stateless；§1.4 LLM governance boundary | 不採用（整合想像見 §2.3：迴圈留在 agent 側） |
| Multi-agent delegation（`orx agent spawn`、orx-agent-delegation） | 同上；MCP 無 write tools | 不採用 |
| Compute orchestration（SSH／Slurm／K8s／Modal／Ray／HF Jobs／managed compute） | 本機單體、無遠端實驗執行概念 | 不採用 |
| openresearch.sh 帳號＋managed compute＋opt-out telemetry（官方 build 預設送 coarse usage events） | local-first、本機優先；#323 egress disclosure 哲學（其 telemetry 雖已 coarse＋opt-out，仍屬外部服務依賴） | 不採用 |
| 桌面 app／`orx` CLI 產品形態 | Browser＋MCP 是唯二介面（§1.3） | 不採用 |
| LaTeX／figures／paper 產出管線（orx-paper／orx-figures） | vault Markdown 必須人類可讀、無私有格式（§1.3） | 不採用 |
| Rust 程式碼本身 | 技術棧 Java 21／Spring Boot 單體（§1.1）；MIT 允許但零可搬程式碼 | **只參考設計與契約，不搬程式碼** |

## 4. 建議行動

1. **（文件動作，local-only）** 將 §2.1（answer 內 observation vs. 推論的呈現規範，additive UX）與 §2.3（本專案作為本機研究 agent 知識後端，經既有 MCP read-only adapter）記入 `.ai_llm_wiki_km` Proposed 區；兩者皆標明 trigger（#678 dogfood B 類 finding）。
2. **（不立項）** §3 全部；§2.2／§2.4 為反向確認＋文字層小借鏡，無獨立工作項。
3. **（交叉引用）** §2.3 與 `2026-09-22-mcp-task-discoverability-evaluation.md` 同池；§2.1 若 dogfood 證實「看不出哪些是推論」的痛點，補進 `ui-gap-analysis-20260912.md` 的 candidate 池；§2.2 的 lineage 慣例供 #679 friction ledger 量大時參照。
4. **（反向貢獻候選，暫不動作）** 反方向（llm-wiki-km → OpenResearch 生態）可想像一個「ground claims in local llm-wiki-km via MCP」的社群 skill——但對外貢獻屬第三方可見寫入（A2），需逐次人類授權，且以 §2.3 的整合先在本機驗證價值為前提。本份評估僅記錄候選，不執行。

## 5. 殘留限制

* 本評估基於 README／`SKILL.md`／`SYSTEM_PROMPT.md`／兩個 agent-skills 的**文件層級**，未安裝或實測 `orx` CLI／desktop app，未驗證 agent 實際遵循 evidence 契約的行為——prompt 契約不等於執行證據（這正是其自身哲學對本評估的提醒）。
* 專案迭代極快（日級 commit；audited revision `951700eff4` 僅代表 2026-10-06 當日狀態），未來立項前應重新核對當時版本與 changelog。
* §2.3 的整合價值取決於本專案 MCP tools 對外部 agent 的實際可用性（discoverability、回應體積、tool 選擇行為），該議題已有專門評估文件（2026-09-22-mcp-task-discoverability-evaluation.md），本份不重複展開。
