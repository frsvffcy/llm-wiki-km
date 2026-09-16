# fireworks-tech-graph 可驗證架構圖與 derived-doc artifact contract evaluation

- 評估日期：2026-09-16
- 外部來源：`yizhiyanhua-ai/fireworks-tech-graph` official repository / README / README.zh / SKILL.md / `scripts/fireworks.py` / `package.json` / CHANGELOG / product site（audited 2026-09-16；stable GitHub Release `v1.2.0` 2026-07-17；npm 僅 legacy `1.0.4`，不得作 Skill authority；license MIT）
- 補充來源：使用者提供之 fireworks-tech-graph 專家盤點敘述（視為 design narrative / historical observation，不作 current contract authority）
- Classification：`TRACK_FULL`
- Current decision：`NO RUNTIME DEPENDENCY；VALIDATED DIAGRAM LOOP = ADOPT AS DESIGN INPUT；DERIVED ANTI-DRIFT = ADOPT AS DOCS GOVERNANCE INPUT；SINGLE PILOT = CONDITIONAL`
- Authority：decision evidence / design input only；不得取代 AGENTS、ADR、production code/tests/CI、GitHub Issue ownership。

## 1. Executive decision

本 Issue 評估的不是「多一個 AI 畫圖工具」，而是把 architecture diagram 從一次性圖片提升為：

```text
source/current architecture facts
→ constrained semantic diagram spec / IR
→ deterministic structural validation
→ SVG render
→ PNG visual readback
→ bounded targeted correction
→ reviewed derived documentation artifact
```

目前 `docs/architecture/system-overview.md` 已是 current Architecture VoT 的責任鏈導航（L0–L5 + owner security/deployment），但主要仍以文字與 ASCII flow 表達；repository code search 目前也沒有 Mermaid diagram owner。這形成一個可評估的 docs readability / onboarding 改善空間，但**diagram 不得成為第二份 architecture authority**。

```text
runtime dependency                         NO-GO
architecture visualization tool            DEFER / PILOT CANDIDATE
validated diagram generation loop          ADOPT AS DESIGN INPUT
derived diagram anti-drift contract        ADOPT AS DOCS GOVERNANCE INPUT
single system-overview pilot               CONDITIONAL / benchmark maintenance cost first
CI auto-regeneration                       DEFER
```

## 2. 官方 current source 交叉檢核（2026-09-16）

### 2.1 專家說明中已過時／需修正的部分

以 current official repository 為準，專家文章的舊 snapshot 必須與 current 分開記錄：

- 不再是 Claude Code-only：current `SKILL.md` / README 明確支援 **Codex + Claude Code**（site 標示 `Codex + Claude Code Skill`；install 以 `skills add ... --agent codex claude-code` 為準）。
- 不再只有 7 styles：current README 宣告 **12 styles（11 generator-backed + 1 AI-authored Dark Luxury）**，其中 9–12 帶 C4 / cloud deployment / event / reliability engineering semantic contracts。舊「7 styles / 5 styles / 8 diagram types」描述只保留為 historical observation。
- 不只 SVG + PNG：current output routes 含 SVG、PNG、offline interactive HTML（pan/zoom/themes/copy，SVG/PNG/JPEG/WebP 至 4×），以及受約束的 semantic SVG→GIF（focused runtime：draw-on + settled data flow，5.75s timeline，Chromium + FFmpeg + ImageMagick）。
- 「沒有 standalone CLI」已不精確：repository 有可直接執行的 `python3 scripts/fireworks.py`，提供 `validate` / `render` / `check` / `export-png` / `export-html` / `animate` / `version` / `doctor` 等命令（`package.json` bin 指向同一 script）。它仍以 Agent Skill 為主要產品形態，但不是只能靠 prompt 才能執行。
- PNG 不再硬依賴 `rsvg-convert`：current README 以 CairoSVG 為 default balance，`rsvg-convert` 為替代路徑，Puppeteer 為高 fidelity manual/browser path。舊 fork 仍寫 `rsvg-convert` 的屬於過時 snapshot。
- Current stable GitHub Release 為 `v1.2.0`（2026-07-17）；npm registry 仍停在 legacy `1.0.4`，不得以 npm version 當 current Skill authority。CHANGELOG 另載明 skills-install 來自 GitHub repo、npm 僅為 package/distribution page。
- License 為 MIT。

### 2.2 真正值得借鏡的 current capability

Current Skill 已有（以 README / SKILL.md / references 為準）：

- versioned diagram IR / schema validation（legacy JSON normalize 至 schema v1；duplicate ID、dangling reference、invalid geometry、non-finite geometry fail-fast）；
- geometry / routing / label / canvas composition checks（orthogonal routing、exact waypoints、port fan-out、label/legend avoidance、bridge jumps、crossings/bridges zero、≤2 bends、spacing/gutter/micro-segment/label-clearance budgets）；
- `text_policy=strict` 可拒絕可見文字截斷；
- PNG export 後讀回 dimensions；
- deterministic checks first + rendered PNG visual readback second；
- visual review unavailable 時明確標示 skipped，不 fake-green；
- targeted repair + bounded convergence（預設至多兩輪）；
- 14 diagram types（全 UML + AI/Agent domain）與 semantic shape/arrow vocabulary（LLM=double-border rect、Agent=hexagon、Vector Store=ringed cylinder；color+dash 編碼 write/read/async/loop）；
- C4 / deployment / event / reliability semantic contracts 要求先驗證 engineering facts，不得自行發明責任、protocol 或 metrics；
- 40+ brand icons（全 styles 共用，不得把 vendor logo 升格 architecture component）。

## 3. 對 llm-wiki-km 的核心 decision

### 3.1 ADOPT AS DESIGN INPUT

#### A. Architecture diagram = derived documentation artifact

Future diagram 只能由 current authority 衍生：

```text
AGENTS / ADR / latest code/tests / Architecture VoT
        ↓
constrained diagram facts
        ↓
SVG / PNG
```

禁止反向：

```text
diagram says X
≠
X becomes architecture truth
```

Architecture facts 仍以現有 authority hierarchy 為準；SVG/PNG 是 read/navigation artifact。任何 diagram 必須明確標注 `GENERATED / DERIVED / NON-AUTHORITATIVE`，並與 Architecture VoT anti-drift 共存（見 §3.1C）。

#### B. 借鏡「Evaluate, don't assert」到 docs visualization

AI 產生圖檔不能因 agent 回覆「完成」就視為正確。若 future adoption，至少需：

1. semantic/structural validation；
2. render success；
3. visual readback 或明確 SKIPPED；
4. source-fact review；
5. bounded correction；
6. latest-main drift check。

此模式和本專案既有 L4/L5 executable evidence / Completion Audit governance 一致。`visual review skipped` 不得寫成 `visual review passed`。

#### C. Architecture VoT 的 anti-drift 可延伸到 diagram plane

若採用 visual docs，應固定：

- diagram scope / abstraction level（例如 C4 Context / Container 或本專案 L0–L5 authority flow）；
- source files / ADR refs / Issue refs；
- generation tool + pinned version/revision；
- semantic input/IR 可追蹤；
- SVG 為 editable/generated artifact，PNG 只作 consumption fallback；
- stale diagram 不得宣稱 CURRENT；
- architecture docs 改變時，需要明確 regenerate/review gate，而不是默默留舊圖。

是否 commit PNG 或只 commit SVG/semantic source 而讓 PNG 成 release/docs build artifact，應在 pilot 中以 artifact size、binary diff 噪音、font/platform 差異作決定（見 §5）。

### 3.2 DEFER / PILOT CANDIDATE

#### D. 為 `system-overview.md` 增加一張 current architecture overview

可評估一個 bounded pilot：只畫一張 top-level diagram，表達（以 current `system-overview.md` L0–L5 + security/deployment 為 fact set）：

```text
Canonical archive/vault
→ SQLite operational state
→ FTS/vector/Graph derived projections
→ retrieval candidates
→ Evidence admission/currentness
→ Grounded Ask

Persistent mutation旁路：
Ask/Repair → Proposal → Draft → Human Review → Publish → canonical
```

要求：

- 不把未實作 capability 加入圖中；
- 不把 provider/vendor logo 誤升格 architecture component；
- Query Transformation 標 default disabled；
- Graph/vector 標 optional/degradable derived modality；
- MCP 標 loopback read-only；
- PRIVATE_INGRESS / owner auth 只在 deployment-view 需要時呈現；
- 一張圖節點過多時拆 view（例如 retrieval-flow view vs governance-flow view vs deployment view），不為追求「全都放一張」犧牲語意；
- zh-TW labels 的字型、斷行、clipping 需在 pilot 中驗證穩定性。

Pilot 只有在 evaluation 證明 maintenance 成本可控後，才另開 adoption Issue。本 Issue 不直接建立 production docs dependency。

### 3.3 NO-GO NOW

- 不把 fireworks-tech-graph 加入 production runtime dependency。
- 不把 generated SVG/PNG 作 architecture/citation/domain authority。
- 不建立 PR 每次自動重生所有 diagrams 的 brittle pipeline，除非先有 deterministic source mapping 與 drift evidence。
- 不依賴 style-specific AI/Agent pattern 自動補元件；所有 nodes/edges 必須來自 project facts。
- 不因外部工具有 OpenAI/Claude logo 就使用品牌風格作 current architecture truth。
- 不讓 visual review 取代 source/architecture correctness review。

## 4. 與現有 evaluation / capability 的關係

- 本 Issue 不重做 #410/#424 Architecture VoT 治理；diagram 是 VoT 的 derived projection，不得另立 authority。
- #439 GitNexus code-graph visualization（navigation only）若未來出現，應承接本 evaluation 的 derived/anti-drift contract，而不是各建一套 visualization governance。
- #443 OpenWiki interactive visualizer 同理：navigation only，承接本 governance。
- C4 / system overview / retrieval flow / governance flow 哪一類最適合本專案，應在 pilot 中以 docs benefit 決定，不因工具支援 14 UML 就全部導入。

## 5. Evaluation / pilot gate

至少回答：

1. 是否能從 current `system-overview.md` / ADR / AGENTS 抽出 bounded fact set，而不讓模型補 invent components？
2. 同一 fact set 在 pinned tool version 下是否可重現語意一致的 SVG？
3. structural validation 能抓到多少 layout 問題，哪些仍需人眼？
4. 一次 current architecture 變更後，diagram 更新成本是否低於手工維護成本？
5. SVG/PNG 在 Git diff / PR review / README/docs rendering 上的可用性如何？
6. artifact size、binary PNG diff 噪音、font/platform 差異是否可接受？
7. 是否需要只 commit SVG/semantic source，而讓 PNG 成 release/docs build artifact？
8. 對 zh-TW labels 的字型、斷行、clipping 是否穩定？
9. 是否能明確標注 `GENERATED / DERIVED / NON-AUTHORITATIVE` 並和 Architecture VoT anti-drift 共存？

若 pilot 值得 adoption，另開獨立 implementation Issue；若無 measurable docs benefit 則 `NO-GO/DEFER`。

## 6. 最終判定

| 項目 | 判定 |
| --- | --- |
| runtime dependency | `NO-GO` |
| architecture visualization tool | `DEFER / PILOT CANDIDATE` |
| validated diagram generation loop | `ADOPT AS DESIGN INPUT` |
| derived diagram anti-drift contract | `ADOPT AS DOCS GOVERNANCE INPUT` |
| single system-overview pilot | `CONDITIONAL / benchmark maintenance cost first` |
| CI auto-regeneration | `DEFER` |

## 7. 對 current roadmap 的影響

本 Issue 為 docs/evaluation，不是 release blocker。Current 主線仍以 latest open release/correctness Issues 為優先；本 evaluation 可平行或於 v0.1.0 READY_TO_PUBLISH 後處理。

不修改 production runtime/default、不新增 dependency、不把本 evaluation 插入 v0.1.0 release scope。

Refs #405、#410、#424、#428、#437。

(End of file)
