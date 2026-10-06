# Re-evaluation：Hindsight agent memory（v0.10.0 → v0.10.2 delta 盤點與判定表重驗）

- 評估日期：2026-10-06
- 性質：**Re-evaluation（delta assessment）**——本 repo 已有 TRACK_FULL evaluation：`2026-09-18-hindsight-agent-memory-evaluation.md`（audited release v0.10.0）。本次任務為重新盤點，範圍＝v0.10.0 之後的 delta＋判定表重驗；不重做全量 audit。
- **Audited release 更新**：v0.10.0（2026-09-14）→ **v0.10.2（2026-09-29）**（中間 v0.10.1 2026-09-21）。repo：Python、MIT、**46,061 stars**、push 2026-10-05——記憶類別生態的頭部專案，迭代極快。
- 結論摘要：**🟡 判定表不變**。v0.10.1／v0.10.2 的 delta 分三類——①後端擴充（Oracle 支援大量修補：企業級 DB 方向，與 local-first SQLite 無關）；②強化（per-scope consolidation strategies、mental-models 防護：off-schema reply 拒絕＋「failed refresh **settles instead of looping on the LLM bill**」、reflect 每次刷新成本 −26%～−43%、coding-agents「memory crediting an **obligation**, not a judgement call」）；③**整合面擴張**（paperclip 插件：run 前 recall／comment 後 auto-retain／agent recall-retain tools；新 harnesses WorkBuddy＋CodeBuddy；hermes 插件）。三點記錄：**(a)** auto-retain 的商業化推進（paperclip「after each comment retains」）正是 2026-09-18 判定表「auto-retain → durable product knowledge：NO-GO」條目的風險面**在生態中持續存在**的證據——不改變判定，但確認該邊界需要持續守住；**(b)**「crediting an obligation」與「settle instead of looping on the LLM bill」是迭代 LLM 迴圈的成本／治理教訓——與本專案 deterministic-over-judgement 哲學同向（反向確認）；**(c)** §9 的六個 pilot trigger 本次盤點**無新增證據**——local-daemon developer pilot 維持 CONDITIONAL／TRIGGER-GATED。不開 Issue、不安裝、不動 production。

## 1. Delta 盤點（v0.10.0 → v0.10.2，release notes 逐項分類）

| 類別 | 內容 | 對本專案的意義 |
| --- | --- | --- |
| 後端擴充 | Oracle backend 大量修補（JSON_MERGEPATCH CLOB、knowledge-base tree 排序、transfer/graph relink/retain 的 Oracle 路徑、advisory lock 修復） | 企業級 DB 方向擴張；與 local-first SQLite 單體無關（§1.1），不影響判定 |
| 強化 | per-scope consolidation strategies（typed API）；mental-models：refuse off-schema delta replies＋re-ask、failed refresh settles（**不再 LLM 迴圈燒錢**）、UTC staleness；reflect 每次刷新 −26%～−43%；api /metrics 移出 event loop | 迭代 LLM 迴圈的成本失控是**該類系統的真實營運風險**（他們自己修的 bug）——反向確認本專案「無自主 LLM 迴圈」邊界的價值 |
| coding-agents 整合 | memory crediting 改為 **obligation**（確定性記帳，非判斷）；新 harnesses **WorkBuddy＋CodeBuddy**；claude-code setup-token 認證；concise extraction 預設 | 確定性記帳＝與本專案 deterministic-over-judgement 同向（反向確認）；harness 擴張使 coding-agent continuity 候選的**覆蓋面**變大，但不改其 DEFER 性質 |
| 整合面擴張 | **paperclip 插件**（`@vectorize-io/hindsight-paperclip`：run 前 recall、**comment 後 auto-retain**、agent recall/retain tools）；hermes 插件 1.0.1；「A Bank Is Now Something You Can Pick Up」（bank/store tenancy 演進） | auto-retain 商業化推進＝2026-09-18「auto-retain → durable knowledge：NO-GO」條目的風險面持續存在；無 agent loop 的本專案不受 org-chart 式整合吸引 |
| 文件／流程 | 測試化 code snippets、互動圖表、docs-skill oracle reference | 無判定影響 |

## 2. 判定表重驗（對照 2026-09-18 evaluation §1 Executive decision）

| Surface | 2026-09-18 判定 | 2026-10-06 重驗 | Delta 註記 |
| --- | --- | --- | --- |
| Hindsight production/runtime adoption | NO-GO NOW | **維持 NO-GO** | delta 為 patch＋整合擴張，無架構級變化 |
| 取代 current Hybrid RAG | NO-GO | **維持 NO-GO** | recall 管線（semantic+keyword+graph+temporal→RRF→rerank）未變 |
| 取代 Retrieval Inspector / Source Locator | NO-GO | **維持 NO-GO** | — |
| Hindsight Knowledge Pages 作 canonical Wiki | NO-GO | **維持 NO-GO** | knowledge pages 仍為 derived views |
| auto-retain 直接寫 durable product knowledge | NO-GO | **維持 NO-GO（風險面擴大）** | paperclip 的「after each comment retains」把 auto-retain 推廣到更多 agent 平台——生態趨勢與本專案治理邊界的對立持續 |
| memory bank / authority separation | ADOPT AS DESIGN INPUT | **維持**（design input 已登記於既有評估） | bank/store tenancy 演進值得未來參考，無新 action |
| source → fact → observation → derived page | ADOPT AS DESIGN INPUT | **維持** | consolidation strategies 類型化與此路線一致 |
| temporal / provenance-aware memory | ADOPT AS FUTURE DESIGN INPUT | **維持** | 與 TimelyRAG 評估的 insertion/event time 之辨同池 |
| coding-agent project continuity | DEFER / OWNER-OPTIONAL BENCHMARK CANDIDATE | **維持 DEFER**（覆蓋面變大：＋WorkBuddy/CodeBuddy） | harness 增加不構成 trigger |
| Cloud memory first pilot | NO CURRENT ADOPTION | **維持** | — |
| local-daemon developer pilot | CONDITIONAL / TRIGGER-GATED | **維持 CONDITIONAL**——§9 六個 trigger 無一成立 | 見 §3 |

## 3. §9 Pilot trigger 重驗（六項，2026-09-18 版）

1. 5–10 個近期 L3/L4/L5 task 重複漏掉已記錄 historical decision——**無新增證據**；
2. 人工反覆重新講解相同 project context——**無新增證據**；
3. agent 反覆重做已否決方案——**無新增證據**；
4. cross-session handoff 成為主要成本——**無新增證據**；
5. AGENTS／Issue／ADR 存在但每次需要過量 context 才能找回——**無新增證據**；
6. owner 明確要求 developer memory experiment——**未發生**。

Trigger 後的第一輪要求（exact pin、local-only、repo explicit opt-in、no Cloud）**不變**；若未來 trigger 成立，delta 中新增的 harnesses（WorkBuddy／CodeBuddy）會擴大可選整合面，屆時納入 benchmark 範圍。

## 4. 建議行動

1. **（文件動作，local-only）** 本次 re-evaluation 作為 2026-09-18 TRACK_FULL 檔的 delta 附錄登記；audited release 指標更新為 v0.10.2；lineage 維持 TRACK_FULL（判定表仍具長期參照價值，不降為 LINEAGE_ONLY）。
2. **（不立項、不安裝）** 2026-09-18 評估的 repository action（1–7）全部維持：不建 production integration Issue、不改 AGENTS.md、不阻塞現行 Sprint。
3. **（盤點節奏建議）** 下次重新盤點的觸發點：major version（v0.11+）、paperclip 式 auto-retain 進入 owner 實際工作流、或 §9 任一 trigger 出現行為證據。以本 repo 的迭代速度（日級 commit、46k stars 生態拉力），建議以「事件驅動」而非固定週期。

## 5. 殘留限制

* Delta 依 release notes（v0.10.1／v0.10.2）＋repo 結構＋paperclip plugin README 核對；paperclip 本體（獨立 agent 平台）與 WorkBuddy／CodeBuddy harnesses 內部未深查——不影響判定表（整合面變化不觸及架構前提）。
* 2026-09-18 評估的深層 audit（lifecycle、authority separation 論證）本次未重做——本次結論建立在「delta 未觸及該 audit 的前提」上；若未來 major version 重構核心 lifecycle，需另做全量 re-audit。
* §9 trigger 的重驗基準是本次會話可查的 repo／issue 證據；owner 日常工作流的實際感受（trigger 1–4 本質是行為觀察）需 owner 自行認定。
* Refs #515 lineage 延續；本篇不建立任何 production adoption Issue。
