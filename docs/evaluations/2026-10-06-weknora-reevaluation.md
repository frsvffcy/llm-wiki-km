# Re-evaluation：Tencent WeKnora（v0.8.0 → v0.8.2 delta 盤點與判定表重驗）

- 評估日期：2026-10-06
- 性質：**Re-evaluation（delta assessment）**——本 repo 已有 TRACK_FULL evaluation：`2026-09-21-weknora-evaluation.md`（audited `da049f04`、v0.8.0）。本次任務為重新盤點，範圍＝該 audit 之後的 delta＋判定表重驗；不重做全量 audit。
- **Audited 更新**：**HEAD `bccb4b151b`（2026-09-30）**；自 2026-09-21 起 **20+ commits**；最新 release **v0.8.2（2026-09-24）**；repo：Go、32,230 stars、push 2026-10-01。**Follow-up 狀態（最重要的 delta）**：既有評估的兩項建議**均已兌現**——#591（P0 corrective：document scope 下推至 KNN storage query，避免 scoped semantic false negative）**於評估當天 16:12 結案**；#566（Product UX Sprint：將架構導向介面收斂為「上傳 → 整理 → 使用」任務流程，即借用 WeKnora 的 processing lifecycle 可見性）**次日結案**。
- 結論摘要：**🟡 判定表不變，且兩項建議全部落地**。WeKnora 側 delta 為修補／強化性質（無 §10 所列的重新評估條件觸發）：①**auth 誠實化**（令牌寫入失敗不再當成登入成功、查詢失敗不再謊稱已撤銷）——與本專案 typed honest failure 契約同向（反向確認）；②**有界讀取修復**（GitLab 檔案下補體積上限，超限報錯而非無界讀入）——反向確認 #287 bounded extraction 的設計（他們撞上無界讀取 bug，本專案 fail-closed by design）；③平台修補（arm64 sandbox images、僅限邀請註冊、docparser AnyDoc 0.2.4、embed 修復）；④**描述演進**——repo description 新增「autonomous reasoning agent」與「self-maintaining Wiki」：其產品方向走向 agentic self-maintenance，與本專案「無 agent loop＋人類治理」的立場維持對照（非 trigger）。不開 Issue、不動 production。

## 1. Delta 盤點（2026-09-21 → 2026-09-30）

| 類別 | 內容 | 對本專案的意義 |
| --- | --- | --- |
| **本專案側（最重要）** | **#591 P0 corrective 當天結案**（document scope 下推 KNN，scoped semantic false negative 已封）；**#566 UX 收斂 Sprint 次日結案**（借用 WeKnora lifecycle 可見性的「上傳 → 整理 → 使用」任務流程） | WeKnora 評估的兩項建議全部轉為 latest main 上的實際變更——evaluation 價值已兌現 |
| Auth 誠實化 | 令牌寫入失敗不再視為登入成功；查詢失敗不再謊稱已撤銷 | **反向確認** typed honest failure 契約（#658／#575 usability 慣例：狀態必須如實） |
| 有界讀取 | GitLab 檔案下載補體積上限，超限報錯而非無界讀入（#3887） | **反向確認** #287 bounded extraction（absolute ceiling fail-closed by design） |
| 平台/部署 | arm64 sandbox images＋Cube variants；僅限邀請註冊檔位；docparser AnyDoc 0.2.4（parse assets once）；embed 修復 | 平台修補，無判定影響 |
| 定位演進 | description 新增「autonomous reasoning agent」與「self-maintaining Wiki」 | 其方向走向 agentic self-maintenance——本專案「無 agent loop＋Proposal → Human Review → Publish」立場維持對照；屬行銷層描述，未見本 audit 範圍內的架構前提變化 |

## 2. 判定表重驗（對照 2026-09-21 evaluation）

| 項目 | 2026-09-21 判定 | 2026-10-06 重驗 |
| --- | --- | --- |
| 平台化 infra（租戶／共享空間／角色／分散式）不搬入 | 不採用 | **維持** |
| 可編輯 derived data | 不採用 | **維持**（derived projection 一律可重建、非編輯面） |
| 自動發布 durable knowledge | 不採用 | **維持**（其「self-maintaining Wiki」方向與此對立，維持不採） |
| 廣泛 Agent/plugin surface | 不採用 | **維持**（MCP read-only 唯一 agent 面） |
| 預設外部 telemetry（Langfuse 等） | 不採用 | **維持** |
| Ingestion lifecycle 可見性 / 失敗與復原證據 / evaluation 可重跑 | 借鏡 | **已落地**（#566/#591） |
| 文件範圍檢索（#591 P0） | corrective | **✅ 已結案** |

## 3. §10 重新評估條件檢查

五項觸發（remote/distributed deployment、多位 operator、本機 diagnostics 不足、write-capable Agent/MCP use case、chunk-policy tuning 成為可量測瓶頸）**均未發生**；WeKnora 側 delta 無實質架構變化。→ 不需重新評估。

## 4. 建議行動

1. **（文件動作，local-only）** 本篇作為 2026-09-21 檔的 delta 附錄；audited HEAD／version 更新為 `bccb4b15`／v0.8.2。
2. **（不立項）** 無新 action 項——兩項建議已兌現，判定表維持。
3. **（盤點節奏）** 事件驅動：§10 五條件任一發生、或 WeKnora 出現觸及「retrieval scope／lifecycle 可見性／evaluation」架構前提的 major 版本時再盤點。

## 5. 殘留限制

* Delta 依 20+ commits 的 commit message＋releases 核對；未逐 commit 讀 diff——#591 的 KNN 下推修正在本專案側已有 regression gate 承接（其 closure 經正常 PR Gate）。
* 2026-09-21 評估的深層 audit（第一手程式碼查核清單）本次未重做；結論建立在「delta 屬修補性質、無架構前提變化」的核對上。
* 其「autonomous reasoning agent／self-maintaining Wiki」目前僅見於描述層；若後續版本實作 agentic self-maintenance 並影響 retrieval/wiki 契約，需重新評估。
