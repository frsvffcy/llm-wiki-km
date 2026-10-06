# Re-evaluation：Wenlan local-first knowledge lifecycle（0.18.10 → 0.18.16 delta 盤點與判定表重驗）

- 評估日期：2026-10-06
- 性質：**Re-evaluation（delta assessment）**——本 repo 已有 TRACK_FULL evaluation：`2026-09-19-wenlan-local-knowledge-lifecycle-evaluation.md`（audited HEAD `47971618`、version 0.18.10）。本次任務為重新盤點，範圍＝該 HEAD 之後的 delta＋判定表重驗；不重做全量 audit。
- **Audited 更新**：version 0.18.10 → **0.18.16**（`Cargo.toml` 已核對）；自 2026-09-19 起 **20 commits**（push 2026-10-06）；repo 79 stars。**Follow-up 狀態**：既有評估的兩個 ACTIONABLE 項**皆已結案**——#541（retrieval benchmark substrate-liveness／environment stamp／channel attribution gate，closed 2026-09-19）與 #542（automation mutation ownership 邊界，closed 2026-09-18；其內容已落地為 AGENTS.md §3 Automation mutation ownership 規則與 `action-risk-autonomy.md` §8）。
- 結論摘要：**🟡 判定表不變，且既有評估的可行動項已全部兌現**。Delta 三類：①**新表面**——experimental **relay**（web AI client 經 relay 連到一個 approved Space，文件明載 privacy limits）：與本專案 localhost-only（#418）路線相反的遠端存取選擇，不改變任何判定；②**安全**——#800 修補依賴並 **guard unauthenticated MCP hosts**：反向確認本專案 MCP adapter「loopback＋bearer auth fail-closed」（#327–#341）的 auth-first 姿態是真實威脅面（他們需要補防護）；③**repair lineage 強化**——#750 durable entity relation source repairs、#802 Markdown projection 修復：既有判定表「explicit supersession／correction history：DEFER IMPLEMENTATION」的外部證據持續累積，但本專案 #384 governed repair path 已涵蓋現行需求，維持 DEFER。**無新 trigger、無新行動項**。

## 1. Delta 盤點（2026-09-19 → 2026-10-06）

| 類別 | 內容 | 對本專案的意義 |
| --- | --- | --- |
| 本專案側（最重要的 delta） | 既有評估的兩個 ACTIONABLE 項已兌現：#541 建立 retrieval benchmark substrate-liveness／environment stamp／channel attribution gate；#542 建立 automation mutation ownership 邊界（現行 AGENTS.md §3） | Wenlan 觀察轉化的治理產出**已落地**——2026-09-18 評估的價值已被實現，不是懸空登記 |
| 新表面 | **Relay（experimental）**：web AI client ↔ relay ↔ 一個 approved Space（電腦在線時）；README 明載 privacy limits 連結 | 遠端存取是 Wenlan 的產品選擇；本專案維持 localhost-only（#418，Mode 2 永 CANDIDATE）——對照組價值，無 action |
| 安全 | #800：dependency patches＋**guard unauthenticated MCP hosts** | **反向確認**：未認證 MCP 暴露是真實威脅面；本專案 MCP adapter 的 loopback＋bearer auth fail-closed（#327–#341）姿態正確 |
| Repair lineage | #750 durable entity relation source repairs；#802 canonical 儲存後修復 Markdown projection | 「explicit supersession／correction history」DEFER 列的外部證據持續成長；本專案 #384（governed repair ingress）已涵蓋現行需求，維持 DEFER |
| Source 攝取 | #801 safe public webpage text previews | 公開網頁文字預覽——與 PixelRAG／NOMAD 評估的 ingest 政策討論同池的溫和版本；本專案無對應 trigger |
| 版本/流程 | 0.18.15／0.18.16 releases；CI Windows cache 修復；setup 文案 | 無判定影響 |

## 2. 判定表重驗（對照 2026-09-19 evaluation §1）

| Surface | 2026-09-19 判定 | 2026-10-06 重驗 | Delta 註記 |
| --- | --- | --- | --- |
| Wenlan runtime / daemon adoption | NO-GO | **維持 NO-GO** | — |
| Rust/libSQL/Tauri stack migration | NO-GO | **維持 NO-GO** | — |
| 第二套 Product Memory / Wiki / Graph / RAG | NO-GO | **維持 NO-GO** | — |
| Source / Memory / Page lifecycle separation | ADOPT AS DESIGN INPUT | **維持（已部分落地）** | #541/#542 即其產物 |
| citation-gated refresh / human-edit review | ADOPT AS DESIGN INPUT（covered stronger） | **維持** | #802（projection 修復）同型問題本專案由 content hash 驗證涵蓋 |
| explicit supersession / correction history | DEFER IMPLEMENTATION | **維持 DEFER**（外部證據＋1：#750） | 本專案 #384 repair path 為現行答案 |
| Spaces / retrieval scope | CURRENTLY COVERED by workspace | **維持** | relay 的 approved-Space 概念仍是其專屬表面 |
| doctor/lint read-only integrity | CURRENTLY COVERED / REINFORCEMENT | **維持** | Vault Lint（#379） |
| OKF interoperability | DEFER / REVISIT TRIGGER STRENGTHENED | **維持 DEFER** | delta 中無 OKF 相關變化 |
| hook / automation mutation ownership | ACTIONABLE → #542 | **✅ 已完成** | AGENTS.md §3 落地 |
| retrieval benchmark substrate liveness / attribution | ACTIONABLE → #541 | **✅ 已完成** | #390 corpus 慣例的基礎設施化 |
| retrieval ranking differential oracle / A / A noise floor / Wenlan benchmark numbers | DEFER / CONDITIONAL / NO-GO | **維持** | — |

## 3. 建議行動

1. **（文件動作，local-only）** 本篇作為 2026-09-19 TRACK_FULL 檔的 delta 附錄；audited version 更新為 0.18.16。
2. **（不立項）** 無新 action 項——兩個 ACTIONABLE 已兌現，其餘判定全數維持；OKF revisit trigger（既有評估所設）未被 delta 滿足。
3. **（盤點節奏）** 事件驅動：relay 若脫離 experimental、repair lineage 若出現本專案 #384 難以涵蓋的形態、或 OKF 採用訊號出現時再盤點。

## 4. 殘留限制

* Delta 依 20 commits 的 commit message＋README／Cargo.toml 核對；未逐 commit 讀 diff——#750／#801／#802 的實作細節未審。
* Relay 的 privacy 文件（`docs/PRIVACY.md`）未深讀——其威脅模型與本專案無直接關係（不採用遠端存取），僅作對照組。
* 既有 TRACK_FULL 的深層 audit（lifecycle／license surfaces／benchmark 方法）本次未重做；結論建立在「delta 未觸及其前提」上——若 Wenlan 出現架構級重構，需另做全量 re-audit。
