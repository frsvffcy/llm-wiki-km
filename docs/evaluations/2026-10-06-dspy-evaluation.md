# Evaluation：DSPy 框架（design-time prompt 優化工具登記；runtime NO-GO）

- 評估日期：2026-10-06
- 來源：https://github.com/stanfordnlp/dspy ——「The framework for _programming_—not prompting—language models」；Python、**MIT**、**38,512 stars**、push 2026-10-05；**audited release：v3.4.0（2026-09-25）**。核心：以組合式 Python 程式（typed **Signatures**＋**Modules**：ChainOfThought／ReAct 等）定義 LM 管線，再用 **Optimizers**（GEPA：reflective prompt evolution；MIPRO：instruction＋demonstration 優化；BootstrapFewShot 等）**對著 metric 編譯 prompts 與 weights**——把 prompt 從手工調參變成有 lineage 的編譯產物。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 runtime 明確 NO-GO、design-time 登記為觸發後可用的實驗工具**。三層結論：①**runtime NO-GO**——Python 框架與 §1.1 Java 技術棧不容；引入即第二條 AI pipeline（§1.4 邊界）；②**反向確認**——DSPy 的 Signatures／structured outputs／Assertions 是「typed 合約＋輸出約束」的一般化，而本專案已用更強的形式實作（deterministic Java citation 驗證＋#658 typed failures，約束在模型外強制）；其「metric-driven 編譯」與本專案 evaluation-first（versioned corpus＋typed metrics）同方向，但本專案刻意**不在 production 設自修改 prompt 迴圈**（Return-or-Revise 評估的 38–46% 有害修訂是直接反例）；③**design-time 價值**——若未來要改進 grounded-answer／insufficient-evidence prompt，DSPy 可作**離線實驗工作台**：對 versioned corpus（#390/#316 慣例）以 typed metrics 跑 optimizer，產出候選 prompt 後**重新進入治理**（prompt 是 code constant，走正常 PR＋regression gate）。成本警告：optimizer 燒 provider 呼叫（GEPA/MIPRO 需大量 LLM 呼叫），僅限離線實驗。不開 Issue、不動 production、不安裝。

## 1. 定位對照

| 向度 | DSPy | llm-wiki-km |
| --- | --- | --- |
| 本質 | LM 程式的 **authoring＋optimization** 框架（Signatures／Modules／Optimizers） | 治理式知識系統（Ask 契約固定、prompt 為 code constants） |
| Prompt 來源 | Optimizer 對 metric 編譯產生（有 lineage：GEPA／MIPRO 論文） | 人類撰寫、經 PR review 變更；行為由 deterministic 驗證約束 |
| 輸出約束 | Signatures／structured outputs／Assertions（框架內 validator） | **更強**：deterministic Java citation 驗證＋currentness 重驗＋typed failure mapping（約束在模型外） |
| 品質改進迴圈 | 機器驅動（optimizer 迭代，燒 provider 呼叫） | 人類驅動、evaluation-first（versioned corpus＋typed metrics＋regression gates） |
| 修正行為研究（Return-or-Revise 評估） | Optimizer 可作為「產生候選修訂」的離線工具 | 生產路徑無修訂迴圈（fail-closed） |
| 技術棧 | Python | Java 21（§1.1）——**runtime 互斥** |

## 2. 可參考之處（依價值排序）

### 2.1 Design-time 實驗工作台（觸發後可用的登記項）

若未來出現「grounded answer 品質需要系統性改進」的需求（#679 dogfood 的 ANSWER_GROUNDING B 類 finding 累積），DSPy 可作**離線 prompt 優化實驗台**：以既有 versioned corpus 的問題集＋typed metrics 為 judge（citation 可定位率、admission rate、#390 慣例），讓 optimizer 產生候選 prompt，**產出物重新進入治理**（人工 diff 審視 → PR → regression gates）——prompt 仍為 code constant，不引入 runtime 自修改。這正是 #390/#408「live-provider controlled measurement」前置條件的具體工具選項；也可直接服務 Return-or-Revise 評估 §3.2 的 paired offline 量測（optimizer 產生候選修訂、paired judge 量 fix/break rate）。**觸發前不安裝、不實驗。**

### 2.2 反向確認：本專案的驗證邊界比框架內建更強

DSPy 的 Assertions／structured-output validation 在框架內 approximates「輸出必須滿足約束」；本專案的 citation 驗證（`mapCitations`＋currentness guard）在 **Java 邊界 deterministic 強制**，失敗即 typed failure、不重試猜測（Return-or-Revise 反證在案）。DSPy 的存在證明「typed 合約＋metric 驅動」是業界收斂方向——本專案早走在同一方向且邊界更硬（§1.4：model-generated metadata 不可為 authority）。

### 2.3 GEPA 論文的方法論登記

「Reflective Prompt Evolution Can Outperform RL」（2025-07）——以 reflection 對 metric 迭代 prompt 的方法論，作為未來任何 prompt 改進工作的引用文獻；與 #390 的 evaluation-first 慣例同方向。

## 3. 不採用之處

| DSPy 慣例 | 本專案 invariant / 定位 | 結論 |
| --- | --- | --- |
| Python framework 作為 runtime 依賴 | Java 21 單體（§1.1）；§1.4 禁第二條 AI pipeline | **runtime NO-GO** |
| Optimizer 在生產路徑內迭代 prompt | prompt 為 code constants；無 runtime 自修改（Return-or-Revise 38–46% 有害修訂反例；§1.4 模型自評不可為 authority） | **不採用**——optimizer 僅限離線實驗 |
| DSPy validator 作為 grounding 證據 | deterministic Java citation 驗證＋currentness 為 authority | 不採用（框架 validator 取代不了 Java 邊界） |
| Optimizer 的 provider 呼叫成本 | #323 揭露義務＋成本治理 | 僅離線實驗可接受；進 production 前須另評 |

## 4. 建議行動

1. **（文件動作，local-only）** 本篇作為「design-time 工具登記」進 evaluation 池：標明觸發條件（ANSWER_GROUNDING B 類 finding 累積＋owner 要求 prompt 品質實驗）、產出物治理路徑（離線實驗 → 人工 diff → PR → regression gates）。
2. **（不立項、不安裝）** 無 runtime 工作項；不建 integration Issue。
3. **（交叉引用）** 與 Return-or-Revise 評估 §3.2（paired 量測的候選產生工具）與 CiteGuard 評估 §3.2（regenerate-once 立項門檻）同池；與 multi-domain retriever evaluation 的「uniform budget」協議原則一致（optimizer 實驗也要固定預算與 corpus）。

## 5. 殘留限制

* 本評估基於 README＋repo metadata（v3.4.0）；未深入審閱 GEPA／MIPRO 演算法細節與其在本專案 corpus 上的實際可行性——3.1 若觸發，第一步是用 20 題級 mini-corpus 驗證 optimizer 的訊號量，再決定是否擴大。
* DSPy 迭代快（月級 release）；實驗時以當時版本重新核對 API。
* 本專案 prompt 目前無 versioned policy 機制（與 chunk／normalization／rerank／context policy 不同）——若 prompt 實驗常態化，應先補 versioned prompt contract（additive，需獨立立項），否則實驗產出無法乾淨落地。
