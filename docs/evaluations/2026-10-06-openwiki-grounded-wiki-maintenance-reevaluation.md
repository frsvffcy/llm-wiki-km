# Re-evaluation：OpenWiki grounded wiki maintenance（2026-09-16 → 2026-10-02 delta 盤點與判定表重驗）

- 評估日期：2026-10-06
- 性質：**Re-evaluation（delta assessment）**——本 repo 已有 TRACK_FULL evaluation：`2026-09-15-openwiki-grounded-wiki-maintenance-evaluation.md`（audited 2026-09-16）。本次任務為重新盤點，範圍＝該 audit 之後的 delta＋判定表重驗；不重做全量 audit。
- **Audited 更新**：**HEAD `c0173dca92`（2026-10-02）**；自 2026-09-15 起 20 commits（push 2026-10-05）；repo：TypeScript、MIT、**16,984 stars**。
- 結論摘要：**🟡 判定表九列全數維持**。Delta 集中在三類——①**host 整合面擴張**（新增 Copilot CLI integration）；②**企業級 model gateway 認證**（Entra ID for OpenAI-compatible gateways）；③**可觀測性**（LangSmith：planner/page workers 命名並按 update 分組成一條 trace thread）＋依賴安全更新（dompurify 3.4.14→3.4.16）。**Grounded Claims／OKF 相關零變化**（commit 掃描無任何 claim／OKF／grounded 關鍵字命中）——既有評估的最高價值候選「Grounded Claims proposition-level model：DEFER / HIGH-VALUE CANDIDATE」的觸發條件未被 delta 推進。不開 Issue、不動 production。

## 1. Delta 盤點（2026-09-15 → 2026-10-02，20 commits 分類）

| 類別 | 內容 | 對本專案的意義 |
| --- | --- | --- |
| Host 整合擴張 | **Copilot CLI integration**（#966）——host-driven integration 清單擴至 Codex／Claude Code／OpenCode／Cursor／Copilot CLI | 印證既有評估「host-agent vs application lifecycle split（ADOPT AS GOVERNANCE INPUT）」的方向：coding-agent 生态持續收斂到「host agent 做研究/撰寫、應用做 durable queue/驗證/finalization」的分工——本專案 MCP read-only 邊界即此分工的介面 |
| Model gateway 認證 | **Entra ID authentication for OpenAI-compatible gateways**（#975） | 企業級 model gateway 認證成為 common need；本專案 #323 的 endpoint 分類（LOCAL_LOOPBACK／REMOTE_SECURE／REMOTE_INSECURE_OPT_IN）未含 Entra/OAuth gateway 流程——若未來 owner 使用企業 gateway，#323 分類需重新檢查（遠端觸發，非現行） |
| 可觀測性 | LangSmith：planner／page workers 命名＋**per-update 單一 trace thread**（#972 系列） | 「一次 update＝一條可追蹤 trace」與本專案 #310（Ask execution metadata）／processing_log 的 per-operation observability 哲學同構——反向確認 |
| 安全/依賴 | dompurify 3.4.14→3.4.16；npm/Actions 依賴批次更新 | dompurify 是其 interactive visualizer 的 XSS 防護——本專案以「零 innerHTML、純 textContent」從源頭免除同類風險（更嚴格，反向確認） |
| Grounded Claims / OKF | **零變化**（commit 掃描無命中） | 最高價值候選的觸發條件未被推進——維持 DEFER |

## 2. 判定表重驗（對照 2026-09-15 檔 §5 最終判定）

| 項目 | 2026-09-16 判定 | 2026-10-06 重驗 |
| --- | --- | --- |
| OpenWiki runtime adoption | NO-GO | **維持 NO-GO** |
| OpenWiki parallel canonical wiki | NO-GO | **維持 NO-GO** |
| Grounded Claims proposition-level model | DEFER / HIGH-VALUE CANDIDATE | **維持 DEFER**（delta 零變化；觸發條件——可量測 gain 且不削弱 authority/governance——未被推進） |
| source-version-driven selective invalidation | ADOPT AS DESIGN INPUT | **維持** |
| resumable per-unit maintenance lifecycle | ADOPT AS WORKFLOW INPUT | **維持** |
| host-agent vs application lifecycle split | ADOPT AS GOVERNANCE INPUT | **維持**（Copilot CLI 擴張加強此判斷） |
| OKF v0.2 interoperability | DEFER | **維持 DEFER** |
| interactive visualizer | DEFER / navigation only | **維持** |
| connector capability isolation | ADOPT AS SECURITY/EGRESS INPUT | **維持**（Entra ID gateway 認證為新增的 #323 未來檢查點，見 §1） |

## 3. 建議行動

1. **（文件動作，local-only）** 本篇作為 2026-09-15 檔的 delta 附錄；audited HEAD 更新為 `c0173dca92`（2026-10-02）。
2. **（不立項）** 無新 action 項；Grounded Claims 的 implementation Story 門檻（可量測 gain＋authority/governance 不削弱）仍未滿足。
3. **（盤點節奏）** 事件驅動：Grounded Claims／OKF 出現機制級變化、或本專案 dogfood 出現「proposition 級 claim 追蹤」的真實需求時再盤點。

## 4. 殘留限制

* Delta 依 20 commits 的 commit message 核對；未逐 commit 讀 diff——#975（Entra ID）與 #966（Copilot CLI）的實作細節未審。
* 2026-09-16 評估的深層 audit（Claims lifecycle／OKF v0.2 語意／connector 隔離）本次未重做；結論建立在「delta 未觸及其前提（claims/OKF 零變化已掃描確認）」上。
* LangSmith 為其 hosted 觀測服務——本專案以 #310 additive metadata／processing_log 自有 observability，無採用問題，僅作哲學對照。
