# twhsi/skills 對 llm-wiki-km 的適用性評估

日期：2026-09-14

## 結論

`twhsi/skills` **有值得借鏡之處，但不建議直接導入其 BIRD／FIRE／iMandalArt／Keyword Graph 作為 `llm-wiki-km` 的 canonical knowledge model、Graph authority 或 retrieval policy**。

真正值得吸收的是它背後的三個工程／知識工作模式：

1. **Workflow-as-Skill**：把反覆出現的知識工作流程沉澱成有明確 trigger、輸入／輸出契約、版本與驗證方式的可重複 artifact。
2. **Machine-readable capability registry**：由單一 Skill source of truth 產生 `agent.json`／`skills.json`／`llms.txt` 與 dependency tree，讓 agent 能查到能力、版本、成熟度、輸出與關係。
3. **Evidence-preserving handoff**：不同處理階段之間保留 source identity、citation、address/route、merge reason、validation evidence，而不是每一階段重新生成一份失去 lineage 的內容。

對 `llm-wiki-km` 的建議是：

- **ADOPT AS DESIGN INPUT**：將「可重複知識操作程序」視為未來可評估的新 layer，但不得直接混入 canonical knowledge authority。
- **ADOPT PATTERN**：若未來增加 export/publishing/workflow capability，優先使用 versioned typed manifest + single-source rendering + explicit validation report。
- **DEFER**：human-curated Knowledge Route／workflow registry／skill registry；先保留為後續 evaluation candidate，不立刻 productionize。
- **REJECT AS AUTHORITY**：BIRD Book Address、FIRE taxonomy、固定八關鍵字 Graph、iMandalArt layout 不得取代現有 application-owned identity、taxonomy、relation profile、Graph projection、EvidenceBundle 或 SourceLocator。

本評估不修改 production code，也不新增 runtime dependency。

---

## 1. External repo 實際模式

### 1.1 Skill 是正式的可重複流程 artifact

`twhsi/skills` 的核心 source of truth 是 `skills/*/SKILL.md`。Skill frontmatter至少描述 `name` 與 `description`，而 `description` 同時扮演 agent routing / trigger 語意。Hermes operating model 明確要求：

- 每個 Skill 只做一件最小有用的事；
- trigger/description 要清楚；
- 修改後需驗證；
- 一個流程重複使用三次以上，才考慮提升成核心 Skill；
- lifecycle 從自然語言 recipe，逐步走到 prompt → structured JSON → Codex Skill → Hermes core view。

這個做法把「流程記憶」從對話／個人習慣移到可版本化 repository artifact。

### 1.2 Registry metadata 是從 Skill source 產生，不靠第二份手工清單

`scripts/build-site.mjs` 會掃描 `skills/*/SKILL.md`，從 frontmatter與 Git history產生：

- skill name / description / version；
- latest revision / updated timestamp；
- axes / resources / repo path / install command；
- `agent.json`；
- `skills.json`；
- `llms.txt`。

這是一個重要模式：**人類維護 workflow source；machine-readable discovery metadata由 build 產生**，避免手寫 registry 與真正 Skill 漂移。

### 1.3 `skill-tree.json` 將 capability 關係顯式建模

其 machine-readable tree 不只列 Skill 名稱，還包含：

- status；
- level/domain/surfaces；
- progress；
- `requires`；
- `unlocks`；
- `supports`；
- `overlaps`；
- `replaces`；
- outputs；
- owner；
- review cycle。

這讓「哪些 workflow 互相依賴／取代／支援」不必只靠 README prose 推測。

### 1.4 BIRD 的價值是「分離多種定位語意」

BIRD 2.1 將：

- structural location；
- semantic index；
- ordered route；
- exact deep link

拆成不同欄位，而不是用單一 id 同時承擔所有角色。

其規範也要求：地址不可靠時標 pending、Deep Link 必須 byte-for-byte 保留、不能因方便自行修補，且 route 不等同 hierarchy。

這個概念值得參考，但 BIRD 格式本身不適合作為 `llm-wiki-km` 的新 canonical identity。

### 1.5 A4 Booklet 的核心不是 PDF，而是 versioned manifest + validation

A4 Booklet 2.2 先將不同來源 normalize 到 `BookletManifest 2.0`，再從同一 manifest 生成 reading PDF、print sheet、DOCX、preview 與 validation report；不同輸出都保留 source refs、citations、routes 與 merge reasons。

Validation 會區分 structure、typography、imposition、visual、source fidelity，並記錄 pass/fail/not-run 與 evidence，而不是只宣稱「已驗證」。

這個 pattern 對未來任何 `llm-wiki-km` export / publishing capability 都有直接參考價值。

### 1.6 Keyword Graph 是 deterministic exploratory projection，但不具 authority 強度

`keyword-graph-view` 以 frequency、term length、spread 與 co-occurrence window 取固定八個 keyword，產生 weighted graph，並附 evidence sentence。

它適合作為快速視覺化／探索工具，但固定八個 keyword 與 co-occurrence weighting 並不足以承擔 `llm-wiki-km` 現有 Graph retrieval / canonical relation / evidence authority。

### 1.7 FIRE 是分析視角，不應直接成為 domain ontology

FIRE 將內容拆為 Fact / Index / Relation / Encyclopedia，並鼓勵對弱 evidence 標 `待證`／`推論`／`需補例`，最後保留 Human Judgment。

這可作為 prompt / review lens，但若直接映射成 persistent schema 或 relation taxonomy，會和目前 Wiki taxonomy、Graph relation profile、Proposal governance 重疊。

---

## 2. 與 llm-wiki-km 現況對照

`llm-wiki-km` 現有架構已比 `twhsi/skills` 更嚴格地處理下列 correctness：

- `vault/` / `archive/` + application-owned records 是 canonical authority；
- SQLite 是 operational/control plane；
- FTS5 / vector / ArcadeDB 是 derived/rebuildable projection；
- persistent mutation 走 Proposal → Draft → Human Review → Publish；
- Graph relation profile與Evidence admission是 application-owned且 currentness-revalidated；
- SourceLocator只負責 navigation，不是 citation identity；
- versioned chunking / context / reranking policies都有 adoption gate / rollback semantics；
- Action Risk A0–A2 已比「80% agent / 20% human」更可執行地定義 mutation approval 邊界。

因此本次不是要把 `twhsi/skills` 當成更高階 architecture，而是要看它是否補足 `llm-wiki-km` 尚未正式建模的一個區域：

> **可重複的知識操作程序本身，是否應成為 versioned、可發現、可驗證的 application object？**

目前 `llm-wiki-km` 很強的是 knowledge object / retrieval / evidence / governance；相較之下，「如何對知識重複執行一套明確程序」主要仍存在於 application service、prompt contract、Issue／docs 與 UI flow，而沒有一個一般化的 user-facing Workflow/Skill layer。

---

## 3. 可借鏡項目與決策

| 外部模式 | 對 llm-wiki-km 的價值 | 決策 |
| --- | --- | --- |
| `SKILL.md`：trigger + bounded workflow + output contract | 可補「可重複知識操作程序」這一層 | **ADOPT AS EVALUATION INPUT** |
| recipe → prompt → structured → skill → core lifecycle | 避免一次性 prompt 過早 productionize | **ADOPT GOVERNANCE IDEA** |
| version + Git revision + updated metadata | 和既有 versioned policy理念一致 | **ADOPT PATTERN** |
| generated `agent.json` / `skills.json` / `llms.txt` | machine-readable discovery 有價值 | **DEFER / 需避免和 MCP capability discovery 重複** |
| dependency tree：requires/unlocks/supports/replaces | workflow composition與deprecated migration 有價值 | **DEFER / FUTURE WORKFLOW REGISTRY** |
| BIRD structural address / semantic index / route / deep link 分離 | 驗證 identity/location/navigation應分離 | **ADOPT CONCEPT，REJECT FORMAT AS AUTHORITY** |
| Human-curated ordered Route | 可成為未來閱讀／研究路徑 UX | **DEFER** |
| Manifest-first multi-output publishing | 未來 export/publishing 非常適合 | **ADOPT PATTERN** |
| validation-report：pass/fail/not-run + evidence | 符合現有 executable evidence 文化 | **ADOPT PATTERN** |
| FIRE Fact/Index/Relation/Encyclopedia | 可作 prompt/review lens | **DEFER，禁止直接成 domain ontology** |
| fixed 8-keyword co-occurrence graph | 快速 exploratory visualization | **NO-GO 作 Graph/RAG authority；可 future disposable UX experiment** |
| iMandalArt fixed 8-angle grid | presentation / thinking format | **NO-GO 作 core architecture；可 future Skill consumer** |
| 70% shorter / 95% retained concise target | 有 output-style idea，但缺本專案 benchmark | **NO-GO 作 default；需 benchmark 才能 adoption** |

---

## 4. 最值得 llm-wiki-km 進一步評估的 capability

### Candidate A — Versioned Knowledge Workflow / Skill Registry

建立一個**非 canonical knowledge authority**的 workflow definition layer，描述：

```text
workflow id
version
status / maturity
trigger / applicability
input contract
output contract
required capabilities
side-effect class / Action Risk
provider egress requirement
policy / prompt revision
validation contract
dependencies / replaces
```

可能用例：

- 將某一類 Source 轉成特定 Proposal；
- 對 Published Wiki 建立固定分析／摘要／review流程；
- 一套有明確 version 的 research/review recipe；
- future export/publish workflow；
- agent可以 discovery，但執行時仍經 application authority。

#### 必守邊界

- Skill/Workflow definition **不是 canonical knowledge**；
- Workflow output 不得直接寫 `vault/`；
- persistent output 仍走 Proposal → Draft → Human Review → Publish；
- Workflow 不得自行定義 relation/taxonomy/citation authority；
- provider output 不得成為 execution authority；
- Action Risk 必須由 application contract決定，不信任 Skill 自述；
- workflow version change不得 silent mutate existing execution semantics。

### Candidate B — Manifest-first Export / Publishing Boundary

未來若加入 EPUB/PDF/knowledge bundle/static export，不要採：

```text
LLM answer
→ directly render PDF/HTML/DOCX
```

建議採：

```text
Canonical Wiki / Sources
→ authority/currentness revalidation
→ versioned ExportManifest
→ render adapters
→ validation report
→ artifact outputs
```

Manifest 可保留：

- canonical knowledge identity；
- source/citation identity；
- requested ordering / route；
- selected revision/hash；
- rendering policy version；
- merge/compression reason；
- output validation evidence。

Renderer只消費已驗證 manifest，不重新決定 knowledge truth。

### Candidate C — Human-curated Knowledge Route

BIRD 的 Route 概念值得和現有 Graph traversal 區分：

```text
Graph traversal
= machine retrieval candidate expansion

Knowledge Route
= human-curated ordered reading/reasoning path
```

若未來實作，Route 應是 application-owned navigation object，引用 canonical identities；不能將 Graph backend RID、raw score或推導 path持久化成 authority。

此能力目前沒有足夠使用需求，建議 **DEFER**，不要因 external repo 有此功能就立刻建立 production issue。

---

## 5. 不建議採用／容易走錯的地方

### 5.1 不要新增第二套 Knowledge Address

`llm-wiki-km` 已有 application-owned Wiki identity、Source Chunk identity、citation identity與SourceLocator。

若再加入 BIRD `Book Address` 作 canonical id，會造成：

```text
canonical id
vs path
vs Book Address
vs deep link
vs graph id
```

多重 authority drift。

若借鏡 BIRD，只借「identity / structural location / navigation / semantic route 分離」這個原則。

### 5.2 不要把 fixed-eight keyword graph 接到 HYBRID_GRAPH

`twhsi/skills` 的 keyword graph 是 heuristic exploratory representation，不具：

- canonical provenance qualification；
- relation-profile version；
- workspace currentness；
- evidence admission；
- citation authority；
- projection snapshot correctness。

因此不得把它當作現有 ArcadeDB Graph projection的新 feed 或 fallback。

### 5.3 不要用 FIRE 取代現有 taxonomy / relation profile

Fact / Index / Relation / Encyclopedia 是人類分析 frame，不是經本專案 corpus驗證過的 ontology。

若 future Skill 使用 FIRE，應只輸出 proposal candidate / ephemeral analysis，不直接建立 persistent relation。

### 5.4 不要另外發布一套和 MCP 重疊的 capability authority

`twhsi/skills` 的 `agent.json` / `skills.json` 對公開 Skill registry 很合理；但 `llm-wiki-km` 已有 MCP tool discovery 與 REST contracts。

如果未來建立 machine-readable Workflow Registry：

- manifest 必須由同一 workflow definitions自動產生；
- 不能和 MCP手寫兩套 capability matrix；
- MCP仍是 remote agent invocation protocol authority；
- registry只負責 domain workflow discovery / metadata。

---

## 6. 自我改善／開發治理可立即借鏡的做法

不必改 production code，也可立即採用以下思路：

### 6.1 三次使用再升格

對新的 prompt／分析手法／agent workflow，不要第一次好用就做成正式 product capability。

可以採：

```text
one-off experiment
→ repeated manually
→ ≥3 useful real cases + stable output contract
→ Evaluation
→ versioned adoption issue
```

這和本專案現有 benchmark-first / adoption-gate文化相容，可降低 speculative feature。

### 6.2 每個流程要有 trigger + output + failure contract

新增 AI workflow 時，不只寫「請分析 X」，而是固定問：

- 何時應觸發？
- 何時不應觸發？
- input authority 是什麼？
- output schema 是什麼？
- failure / unavailable / stale 怎麼表示？
- 是否有 provider egress？
- 是否產生 mutation？Action Risk 是什麼？
- 什麼證據才算完成？

### 6.3 Metadata 盡量 generated，不要手工同步

若 future workflow registry 同時有 Browser、MCP、docs 或 agent manifest，應採：

```text
single workflow definition
→ generated projections
```

不要讓 README、Browser選單、MCP description、agent manifest各自硬編 workflow name/version/dependency。

---

## 7. 建議後續 Issue 候選

目前**不建議因本評估直接 productionize整套 Skill framework**。

只有當實際出現至少三個需要相同 execution lifecycle 的可重複 knowledge workflows 時，再建立：

```text
[L4][Architecture][Evaluation][Knowledge Workflow]
評估 versioned Knowledge Workflow／Skill Registry 的 application-owned contract、governance 與 MCP/Browser 邊界
```

Evaluation應回答：

1. 是否真的有 ≥3 個 production-relevant workflow 可共用同一 abstraction；
2. workflow definition 是 file-based、DB-based或其他 application-owned source；
3. version/currentness/rollback語意；
4. trigger/applicability是否 deterministic；
5. provider egress與Action Risk；
6. workflow → Proposal/Draft 的合法 ingress；
7. Browser/MCP如何只投影 capability而不變第二份 authority；
8. 是否會淪為 generic agent framework / prompt marketplace。

若需求不足，維持現況比建立抽象更合理。

若未來正式進入 export/publishing需求，則另開：

```text
[L3][Architecture][Evaluation][Export]
評估 versioned ExportManifest、multi-format renderer 與 artifact validation boundary
```

這個候選比 generic Skill framework更容易 bounded，也更直接繼承目前 citation/currentness/governance優勢。

---

## 8. Final Decision

### GO — 借鏡 pattern

- workflow-as-versioned-artifact；
- generated capability metadata；
- explicit dependency / replacement relationships；
- evidence-preserving handoff；
- manifest-first multi-output generation；
- validation report with actual evidence；
- human judgment與agent repeatable structure分離。

### DEFER — future evaluation candidate

- generic Knowledge Workflow / Skill Registry；
- human-curated Knowledge Route；
- machine-readable workflow discovery projection；
- export/publishing subsystem。

### NO-GO — 不直接採為 llm-wiki-km authority

- BIRD Book Address作 canonical identity；
- FIRE作 persistent ontology；
- fixed-eight keyword graph作 Graph/RAG authority；
- iMandalArt作核心 knowledge schema；
- 未 benchmark 的固定 compression ratio；
- 與 MCP 重複的第二份 capability authority。

總結：`twhsi/skills` 對 `llm-wiki-km` 最大貢獻，不是某一個知識卡格式，而是提醒我們：

> **除了「知識本身」需要 version、authority、provenance、currentness，反覆執行在知識上的「程序」也可以成為有版本、可驗證、可發現的正式 artifact。**

這個方向與 `llm-wiki-km` 的 local-first、provider-neutral、application-owned authority、Human Review 與 benchmark-first治理相容；但目前證據只足以進入 future architecture evaluation，不足以直接建立 production Skill runtime。
