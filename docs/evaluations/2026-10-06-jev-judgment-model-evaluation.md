# Evaluation：Jev 判斷模型（TypeSafe AI「System One」）——決策原語與 RAG 路由的概念對照

- 評估日期：2026-10-06
- **來源更正（重要）**：使用者提供的 URL（Tencent/WeKnora）已於 `2026-10-06-weknora-reevaluation.md` 完成重新盤點（判定表不變）；專家說明描述的是 **Jev——TypeSafe AI 的「System One」決策模型（2026-09 發布）**，與 WeKnora 無關。本評估以 Jev 為對象。
- **真實性查證**：Jev 為**可驗證的真實產品**——多個第三方獨立來源報導（Braintrust「What is Jev? A guide to AI that makes decisions」2026-09-26、EdenAI、Hugging Face model page、Maxim、Befailproof 等）。已核對的核心特性：三個 typed 原語——**`Choice`**（1–255 選項、回傳機率分佈＋confidence）、**`Score`**（2–10 級序數量表＋confidence）、**`Noul`**（無需預設標準的 null judgment，不確定性直接表達於回傳機率本身）；平行評估（多個獨立問題單次呼叫）；定位為「calibrated decision primitive」。
- **可信度分級**：產品存在性＝高（多來源）；**延遲（70–500ms）與「0% structured-output errors by construction」＝vendor/二手來源宣稱**，未經本專案獨立 benchmark；「0% 結構錯誤」是 constrained decoding 的設計性質（可採信），但**決策誤差（misclassification）依然存在**——專家自己的修正，也是本評估採納的前提。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 概念層三個 design input 登記；四層式架構的 runtime 採用 NO-GO NOW**。Jev 的價值主張（把高頻、重複的判斷外包給低延遲 typed 決策原語）對應本專案三個既有 governed slots：①**Score → versioned rerank policy 候選**（#326 形狀：reorder-only、benchmark-first、typed no-op；model-based score 屬新 policy 類別，需 #316 級 benchmark＋新 #323 provider 類別，DEFER）；②**Noul → INSUFFICIENT_EVIDENCE pre-gate 候選**（現行判定為 deterministic fail-closed；model gate 省成本但引入 false-refusal 風險——誤拒率指標已在 rerank-late-interaction 評估 §3.1 登記，DEFER）；③**Choice intent routing → 不採用**（與 #390/#408 query 側邊界直接衝突；#408 KEEP DISABLED 的紀律完全適用）。專家對「零幻覺」的修正（**0% 結構錯誤 ≠ 0% 決策誤差**，必須設 threshold＋fallback）正是本專案 §1.4「model-generated metadata 不可為 authority」的具體化——任何採用都必須保留此治理層。其 GitOps 知識更新流程中的 **auto-merge（Jev 自動審核合併）明確不採用**（違反 Proposal → Human Review → explicit Publish）；可借鏡的是 **minimal-mutation 編輯合約**（作為 #374 Draft 生成 prompt 的 design input）。

## 1. Jev 是什麼（經第三方來源核對）

| 原語 | 行為 | 回傳 |
| --- | --- | --- |
| `Choice` | 從 1–255 個預設選項中單選（不會捏造選項外答案） | 選項機率分佈＋confidence |
| `Score` | 對有序量表（2–10 級）評分 | 等級機率＋confidence |
| `Noul` | 無需預設標準的 null judgment（「是否成立」型判斷） | 0–1 機率（不確定性內建於機率） |

賣點：**決策而非對話**——不生成文本，輸出按 construction 受 typed 約束（無結構錯誤、無 JSON 破格式、無選項外捏造）；平行評估、低延遲（vendor 宣稱 70–500ms）、低成本。**但 misclassification 依然存在，且可能對錯誤選項給出高信心**——工程上必須 threshold＋低信心 fallback（專家修正，本評估採納為前提）。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

| 專家建議的用法 | llm-wiki-km 現況（2026-10-06 main） | 判定 |
| --- | --- | --- |
| **Choice：查詢意圖路由**（WIKI_LOOKUP/RAG_SEARCH/CHITCHAT/ESCALATE 分流） | #390：production 唯一 query 側轉換＝`cjk-bigram-v1`＋#605 fallback；#408 query transformation `KEEP DISABLED`；查詢範圍／mode 由**使用者顯式選擇**（RetrievalRequest、document scope） | **不採用 NOW**——新 query 側行為＋每 Ask 新 egress＋misclassification 導向錯誤子系統；#408 紀律完全適用（除非未來 benchmark 顯示可量測 gain 且走 versioned policy） |
| **Score：擴大 top-k 後的 model rerank**（over-fetch 15–20 → 並行 Score → 取前 5） | #326 `rerank-policy-v1-exact-anchor`：deterministic、reorder-only、**無 raw-score blending**、typed no-op；窗口擴大＋rerank 已登記為 rerank-late-interaction 評估 §3.1 條件②（deterministic 變體優先） | **DEFER / versioned policy 候選**——model-based score 屬新 policy 類別：需 #316 級 benchmark（per-case、零 correctness regression）、#323 新 provider 類別、misclassification threshold 治理；deterministic 變體未證明不足前不引入 |
| **Noul：可答性判斷 pre-gate**（Context 送 LLM 前攔截） | `INSUFFICIENT_EVIDENCE` 已存在（deterministic：insufficientEvidence＋空 blocks＋#660 citation invariant）；「INSUFFICIENT_EVIDENCE 誤拒率」指標已登記（rerank-late-interaction 評估 §3.1） | **DEFER / 觸發後候選**——第二模型呼叫省 token 但引入 false-refusal（safety-coverage trade-off，CiteGuard 評估已論證）；僅在 provider 成本／延遲成為量測到的問題、且誤拒率量測可接受時考慮 |
| **GitOps 知識更新：agent 編輯 → PR → Jev 自動審核 → auto-merge → CI 重建索引** | Proposal → Draft → **Human Review** → **explicit Publish**（#374；#370 核准不自動發布）；vault 為 SoT＋projection 可重建 | **auto-merge 部分明確不採用**（人類審核為強制關卡）；「Git 為 SoT＋CI 重建 projection」＝**反向確認**本專案既有架構 |
| **Minimal-mutation 編輯合約**（最小改動、結構凍結、矛盾覆寫、零對話輸出、XML 定界、提交前結構驗證） | #374 Draft 生成／regenerate／preview／diff 流程已存在；prompt 契約細節未對外揭露 | **ADOPT AS DESIGN INPUT（DEFER 實作）**——作為 Draft 生成 prompt 的設計輸入候選（減少無關改寫、diff 更乾淨利於人類審核） |
| 專家修正：「零幻覺」應定義為「0% 結構錯誤」，非「0% 錯誤」 | §1.4：model-generated metadata 一律不可為 authority | **同構採納**——Jev 判定若進任何 production 決策點，必須 threshold＋deterministic fallback＋可觀測（#310 metadata），不得以模型 verdict 直接取代既有 deterministic 契約 |

## 3. 建議行動（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **登記為 rerank 候選池的新 policy 類別**：`Score`-based model rerank（over-fetch＋judgment filter）與 deterministic 變體（rerank-late-interaction 評估 §3.1 條件②）**並列**為候選——benchmark 時兩者同跑，誰有可重現 gain 誰上；Jev 變體多兩個前置（#323 新 provider 類別＋misclassification threshold 治理） | 觸發：TAGGRAPH／檢索策略合併實驗顯示現行 deterministic rerank 有不可癒合缺口 |
| 3.2 | **Noul pre-gate 登記**：觸發條件＝provider 成本／延遲成為量測問題；採用前必須量測誤拒率（指標已存在）且保留 deterministic `INSUFFICIENT_EVIDENCE` 為 fallback | DEFER |
| 3.3 | **Minimal-mutation 編輯合約**：#374 Draft 生成 prompt 的設計輸入（最小改動原則／結構凍結／矛盾覆寫／零對話輸出）——可讓 diff 更乾淨、人類審核更高效 | 文件層設計輸入；下次觸及 Draft prompt 時評估 |
| 3.4 | **反向確認登記**：「Git 為 SoT＋CI 重建 projection」與本專案 vault＋rebuildable projections 同構；「決策誤差 ≠ 零幻覺」與 §1.4 同構 | 無 action |
| 3.5 | **不採用**：Choice intent routing（#408 紀律）；Jev auto-merge（人類審核強制）；以其 vendor 延遲／成本數字作決策依據（未獨立驗證） | 明確不採用 |

## 4. 殘留限制

* Jev 特性依第三方報導與 vendor 宣稱核對（多來源一致），**未實測 API**——延遲、校準品質、CJK 表現、pricing 全部未驗證；3.1/3.2 若觸發，第一步是 20 題級 mini-corpus 實測校準與誤拒率。
* 專家說明中的 Python 範例（`jev_client`）為其自擬的示意碼，非官方 SDK 依據。
* 「0% structured-output errors by construction」是 constrained decoding 的設計宣稱——即使成立，也僅涵蓋結構面，決策誤差治理（threshold／fallback／量測）在任何採用中不可省略。
* 與既有評估的關係：本篇補完「檢索鏈路評估池」的**判斷原語**層——deterministic rerank（#326）vs model rerank（CiteGuard §3.3／本篇 Score）、deterministic 拒答（#660/#670）vs model answerability gate（本篇 Noul）；所有 model-based 候選共用同一治理前提：**模型判定不得取代 deterministic authority，只能在其下做可量測的加速/過濾**。
