# Evaluation：Return or Revise? Learning When Revision Helps Retrieval-Augmented QA（答案修訂決策的負面結果）

- 評估日期：2026-10-06
- 來源：arXiv:2609.30087v1（2026-09-24 提交，25 pages；AFRL 資助、cleared for public release）。**程式碼可得性已核對**：abstract 與全文皆**無任何 repo 連結**（僅 HF 模型頁與引用文獻連結）——專家「未確認有完整重現程式碼」屬實，不可重現。評估輸入含使用者提供的專家說明，其引用的關鍵數字（約 2.6 萬題、38–46% 有害修訂仍被執行、standard-RAG 答案存在時修訂無顯著增益）**經摘要與全文核對全部成立**（精確值：25,870 held-out 題、policy 收斂 35.9–41.4% oracle gap、draft vs standard-RAG 二選一強 ~2 分（Llama）／~4 分（OLMo）、第三選項無顯著增益）。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 本篇的定位是既有評估池的反方證據**：2026-10-06-citeguard-rag-paper.md §3.2 登記了「validation-guided regenerate-once」候選（CiteGuard 主張驗證引導重產可封住 grounding 風險），本篇以 paired-effect 量測給出直接制衡——**即使訓練過的修訂選擇器仍執行 38–46% 的有害修訂**；且**當標準 RAG 答案已在備選集合中時，加入修訂候選無顯著增益**。本專案的 grounded answer（citation 驗證＋#670 currentness 重驗）正是那個「standard-RAG answer」，因此此反證對本專案的適用性比對一般系統更強。直接推論：①現況「deliver validated answer 或 fail-closed」二元設計中「保留原答案」的價值獲正面支持；②未來任何「引用驗證失敗→自動重寫」提議，立項門檻提高為 **paired offline 量測（fix-rate／break-rate）先證明淨值，且「保留原答案／拒答」必須是一等選項**。可信度中等（僅短篇英文開放域 QA、無重現程式碼、未測長回答／中文／引用契約）。不開 Issue、不動 production。

## 1. 論文要點（正確性經摘要＋全文核對）

- **問題設定**：不問「草稿有沒有信心」（draft confidence 量「現在對不對」），問「**用檢索證據修改草稿，效果是變好還是變壞**」。提出 **recoverability**：把原答案與其候選修訂放在**同一 correctness judge** 下成對評分，使 repair（修好）、harm（改壞）、oracle gap 三者可分別觀察；再訓練 policy 在修訂**前**預測 recoverability。
- **規模與結果**：25,870 held-out 開放域問題、三種修訂設定、三個 generator/refiner 家族。paired-outcome scorer 在全部 9 個 Llama setup-seed fits 的 accuracy–revision-rate 曲線下積分優於 matched draft-correctness scorer；dev thresholds 下平均 +0.23–0.68 accuracy points（跨訓練 runs 僅 dense retrieval 顯著）；policy 優於 always-revise（+1.10–1.33 points）、平均收斂 35.9–41.4% oracle gap，**但仍執行 38–46% 的有害修訂**。
- **備選集合實驗（§5.6）**：當一個 **draft-free standard-RAG answer** 也在選項中，「draft vs standard-RAG 二選一」比加入修訂候選強約 2 分（Llama）／4 分（OLMo），**第三選項（修訂）無顯著增益**。
- **核心命題**：「Recoverability describes one revision; its value as an available action **also depends on the alternatives**.」——修訂的價值只能在既有備選集合的相對關係中評估；離線成對評分每個可用答案，使這種比較可訓練、可量測。
- **Limitations（原文）**：僅涵蓋 **short-form open-domain QA**；未測 long-form、multi-hop、domain-specific、high-stakes 設定。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

| 本篇發現 | llm-wiki-km 現況（2026-10-02 main） | 判定 |
| --- | --- | --- |
| 即使 learned policy 仍執行 38–46% 有害修訂 | **本專案無任何答案修訂／重寫路徑**——失敗一律 fail-closed（`PROVIDER_INVALID_RESPONSE` typed、`INSUFFICIENT_EVIDENCE` 零 citation、#670 provider 前 currentness 重驗） | 現況二元設計獲正面支持 |
| standard-RAG 答案存在時，修訂候選無顯著增益 | 本專案的 grounded answer（deterministic citation 驗證＋currentness guard）**就是**論文中的 standard-RAG answer，且驗證更強 | **直接降低 CiteGuard 評估 §3.2 regenerate-once 候選的預期淨值** |
| 「動作的價值取決於備選集合」 | 本專案備選集合＝validated answer／typed failure／INSUFFICIENT_EVIDENCE；#390 blocking gates（rewrite 零增益時逐 identity 對齊 baseline、forbidden identity）已具「相對既有備選證明淨值」的雛形 | 已有雛形，框架語意可借（§3.3） |
| Paired-effect 量測（同一 judge 下 repair／harm／oracle gap 分開觀察） | #390 是 identity 層 paired（baseline vs rewrite 逐 identity 對照），但 **answer 層的 fix-rate／break-rate 框架未使用** | 評測模式借鏡（§3.2） |
| Draft confidence 不足以支撐修訂決策（paired-outcome scorer 才優於 confidence scorer） | 模型自評信心在本專案本就不可為 authority（§1.4 model-generated metadata 不可信） | 同構確認 |
| 僅短篇英文開放域 QA、無重現程式碼 | 本專案：長回答、CJK、deterministic citation contract、currentness 維度——論文完全未涵蓋 | 外推受限（專家同此），數字只作方向性證據 |

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **登記為 CiteGuard §3.2 的反方證據**：兩篇合讀后，regenerate-once 候選的立項門檻確立為——paired offline 量測（fix-rate／break-rate＋provider 呼叫成本）先證明淨值，且「保留原答案／拒答」為一等選項、有害修訂率為 blocking metric。批次 docs PR 時建議在 CiteGuard 檔 §3.2 回填一行交叉引用 | 文件動作；無程式碼 |
| 3.2 | **Paired offline 修訂量測（專家最小實驗的正式化）**：若未來 dogfood 出現「答案常以可修方式出錯」的 B 類 finding——離線保存原答案、修訂答案、人工核對的證據判定；同一 judge 下量測 fix-rate 與 break-rate；**完全不接入正式回答路徑**；成本＝每次量測額外 provider 呼叫＋標註人力，風險＝錯誤修訂只存在於離線產物 | Trigger-gated；若執行，屬 `[L3]` evaluation issue（離線、無 production 變更） |
| 3.3 | **「Alternatives-first」決策框架**：未來任何 answer-path 增強（rewrite／regeneration／rerank 升級）的立題固定為「相對於既有備選集合（validated answer／typed failure／INSUFFICIENT_EVIDENCE）的淨值為何」，而非「新功能本身品質如何」。#390 gates 已具雛形；此框架可寫進未來 evaluation contract | 文件層借鏡；下次觸及 evaluation 契約時一併 |
| 3.4 | 「把每個可用答案離線成對評分，使比較可量測」的方法論（recoverability） | 作為 3.2 的量測設計藍圖；無獨立 action |

## 4. 不建議採納之處

- **「引用驗證失敗 → 自動重寫」的直覺設計**：本篇數據（38–46% 有害修訂執行率、standard-RAG 存在時無增益）直接反對未經 paired 量測的自動重寫。#658 的 typed failure 語意（可見的 operator-safe 失敗、使用者可重問）比隱形重寫更可治理、更符合 #282 邊界。
- **以 draft confidence／模型自評作為修訂觸發**：模型自評不可為 authority（§1.4）；且論文顯示即使在其設定下訓練出的 paired-outcome scorer 也不夠好（38–46% 有害修訂）。
- **外推其數字到本專案**：短篇英文開放域、closed-book draft、無 citation contract、無 currentness 維度；本專案任何修訂產物還要過 deterministic citation 驗證——有害修訂率可能不同（更好或更糟），必須本地量測，不得引用其百分比作為決策依據。
- **把修訂當作降低 `PROVIDER_INVALID_RESPONSE` 的手段**：invalid response（超長、壞 citation）是 typed 契約問題，重寫不改變契約違反的本質；正確路徑仍是 fail-closed＋可觀察診斷。

## 5. 殘留限制

* 摘要＋全文關鍵段落核對（25,870 題、38–46%、35.9–41.4% oracle gap、§5.6 備選集合實驗、Limitations 原文）；未逐表重算數據、未執行任何重現（無程式碼可重現——已核對）。
* 正確性 judge 為其自設（論文未提供 judge 的外部效度證據）；repair／harm 的絕對值依賴該 judge，趨勢結論（有害修訂率高、備選集合效應）比絕對值可信。
* 短篇英文開放域 QA 的結論對本專案（長回答、CJK、citation contract、currentness）外推受限；3.2 的本地量測是任何後續決策的必要前置。
* 與既有評估的關係：本篇是 2026-10-06-citeguard-rag-paper.md §3.2 的反方證據、與 2026-10-06-multi-domain-retriever-evaluation-paper.md（評測協議）同屬 evaluation 方法学輸入池；三者合讀構成「要不要修訂答案」的完整證據面。
