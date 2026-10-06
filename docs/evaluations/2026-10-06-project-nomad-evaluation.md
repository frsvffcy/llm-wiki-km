# Evaluation：Project NOMAD（offline-first 知識伺服器——最接近的兄弟產品對照）

- 評估日期：2026-10-06
- 來源：https://github.com/Crosstalk-Solutions/project-nomad ——「offline-first knowledge and education server：Wikipedia、thousands of books、courses、maps、optional local AI，全部跑在自有硬體上、無需網路」；TypeScript、**Apache-2.0**、**39,118 stars**、2025-06-24 建立、**audited revision：`54ee30a449`（2026-10-05 push）**、homepage projectnomad.us。架構：Docker 容器聯盟（Command Center 管理 UI＋API，核心含 MySQL＋Redis），編排 COTS 工具——**Ollama**（或 LM Studio／llama.cpp 等 OpenAI-compatible）＋**Qdrant**（AI Chat with KB：文件上傳＋語意搜尋 RAG）、**Kiwix**（離線 Wikipedia／醫療參考／電子書 ZIM）、**Kolibri**（Khan Academy 課程＋多用戶）、ProtoMaps（地圖）、CyberChef、FlatNotes、Supply Depot（一鍵 app 目錄）。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 本系列與 llm-wiki-km 定位最近的「兄弟產品」——同為本地優先知識伺服器，但寫入路徑相反**：NOMAD 是打包公開語料的**消費／分發**伺服器（離線教育／備援、多用戶、無寫入治理），本專案是個人知識的**創造與治理**系統（單 owner、Proposal → Publish、citation currentness）。三個實質借鏡：①**全離線 Ask 的完成路徑已確認為「配置而非開發」**——本專案 provider 層本就支援 OpenAI-compatible 自訂 base-url（`application.yml:32-43`、`OpenAiCompatibleHttpTransport` 已核對），指向本機 Ollama 的 `/v1` 端點（#323 `LOCAL_LOOPBACK` 分類既有）即可閉環，值得寫成 config recipe 文件；②**collection-manifest 模式**（`collections/*.json`＋creator-pack-license 的 license metadata）作為未來任何「有界公開 corpus 匯入」的設計輸入（DEFER）；③**硬體資源信封公開慣例**（無 AI：5GB/1GB vs 有 AI：25GB/32GB＋GPU 的誠實文件）——docs-level 借鏡。反向確認：NOMAD 需要 Docker 聯盟（MySQL＋Redis＋多容器）正是 COTS 工具聯盟的必然成本——本專案「單體＋SQLite」在單人治理場景是對的。不開 Issue、不動 production。

## 1. 定位對照

| 向度 | Project NOMAD | llm-wiki-km |
| --- | --- | --- |
| 本質 | 離線教育／備援的內容**消費**伺服器（預打包公開語料） | 個人知識**創造與治理**系統（owner 自己的文件 → governed vault） |
| 使用者 | 多用戶（Kolibri 多用戶進度） | 單一 owner（#417 owner security boundary） |
| 內容來源 | Kiwix ZIM（Wikipedia／醫療／生存指南）、Kolibri 課程、地圖＋使用者上傳文件（AI KB） | inbox/archive 上傳 → extraction → vault（citation 錨 canonical chunk） |
| AI stack | Ollama／LM Studio／llama.cpp＋Qdrant 語意搜尋 RAG——**無 citation currentness 治理、無 proposal 流程**（README 層級） | provider-neutral grounded Ask：deterministic citation 驗證＋#670 currentness 重驗＋#649/#652 publish TOCTOU 防護 |
| 架構 | Docker 容器聯盟（Command Center＋MySQL＋Redis＋各 COTS 容器） | Spring Boot 單體＋SQLite 單檔（WAL） |
| 硬體信封 | 明確發布（無 AI：2GHz/4GB/5GB；有 AI：Ryzen 7/32GB/RTX 3060/250GB，並誠實說明模型大小＝記憶體需求） | 未發布資源信封 |
| 寫入治理 | 無（內容由集合清單決定）；筆記（FlatNotes）與知識庫分離 | 寫入是產品核心（治理流程即差異化） |
| 平台 | x86-64 Debian＋Docker（macOS／ARM 非官方支援） | Java 21 任何平台；localhost-only 綁定 |
| 授權／規模 | Apache-2.0、39k stars、活躍（Crosstalk Solutions 維運） | — |

## 2. 可參考之處（依價值排序）

### 2.1 全離線 Ask 的完成路徑＝配置而非開發（docs-level，本篇最可立即行動）

NOMAD 用 Ollama／OpenAI-compatible 端點做本機 AI——本專案的 provider 層**本就具備同一能力**：`application.yml:32-43` 的 `EMBEDDING_PROVIDER_BASE_URL`／`ANSWER_PROVIDER_BASE_URL` 可指向任意 OpenAI-compatible 端點（`ai/answer/provider/openai/OpenAiCompatibleHttpTransport.java` 等 adapter 在案），指向本機 Ollama 的 `/v1` 即為 `LOCAL_LOOPBACK`（#323 分類既有、Browser indicator 已呈現）。**意義**：全離線 Ask（無任何外部 egress）是現有能力的一個配置配方，只缺一份 recipe 文件（建議模型、`LOCAL_LOOPBACK` 揭露行為驗證、CJK 模型品質注意、embedding 維度與 sqlite-vec 相容性檢查）。這同時滿足 LEANN 評估（2026-09-14）多次指出的「local embedding provider 前提」——一條路徑兩個收益。**建議**：作為 docs-only 小任務登記（README 使用方式或 guides 一節），非 production 程式碼變更。

### 2.2 Collection-manifest 模式（design input，DEFER）

其 `collections/` 以 JSON manifests 宣告內容集（`wikipedia.json`、`kiwix-categories.json`、`creator-packs.json`＋**`creator-pack-license.md` license metadata**）——宣告式、有界、license-aware 的打包內容。對本專案：若未來出現「匯入有界公開語料為 **read-only source corpus**」的需求（與 PixelRAG／ragflow／TimelyRAG 評估的 ingest 政策討論同池；#678 的 egress／policy 框架），manifest 模式（有界清單、宣告式、license 欄位必填、版本化）是正確形狀。**DEFER**——個人知識庫引入公開語料會改變 SQLite／FTS 規模假設與產品定位，需 dogfood 證據（「我需要查外部參考資料但不想離開系統」的 B 類 finding）。

### 2.3 硬體資源信封公開慣例（docs-level 借鏡）

NOMAD README 的 minimum／optimal 規格表＋「AI Assistant 是把記憶體需求推高的唯一原因」的誠實說明＋三個價位帶的硬體指南——本專案可發布同型信封（無 provider 模式 vs 配置 provider 模式的資源差異；Java 21＋SQLite 的輕量基線；sqlite-vec 維度與 corpus 規模的儲存估算——LEANN 評估已算過 30MB–600MB 區間）。低成本、高透明度。

### 2.4 反向確認（無 action）

- NOMAD 的 Docker 聯盟（MySQL＋Redis＋每工具一容器）是「COTS 工具聯盟」的必然成本——本專案「單體＋SQLite」在單人治理場景的正確性獲對照組支持。
- NOMAD 的 RAG（Qdrant 語意搜尋）在 README 層級**無 citation currentness 治理**——「場景決定治理密度」：教育消費不需要、個人可信知識需要。本專案的 grounded 契約複雜度是產品定位的必然，不是過度設計。

## 3. 不採用之處

| NOMAD 慣例 | 本專案 invariant / 定位 | 結論 |
| --- | --- | --- |
| Docker 容器聯盟（MySQL＋Redis＋多 COTS 容器） | 單體＋SQLite 單檔（§1.1） | 不採用——反向確認既有選擇 |
| 多用戶支援 | 單 owner boundary（#417） | 不採用 |
| 打包公開語料作為主要內容模式 | 個人知識創造為核心；公開語料僅能是未來的 read-only 附加（§2.2） | 不採用為產品模式（manifest 模式保留為 design input） |
| Qdrant 外部向量庫 | sqlite-vec embedded（replaceable projection，ADR 已定） | 不採用 |
| FlatNotes／Kolibri／CyberChef／Supply Depot 等功能面 | 產品邊界：知識治理＋Ask＋MCP，非通用工具箱 | 不採用 |
| x86-64 Debian＋Docker 部署形態 | Java 21 跨平台、localhost-only | 不採用 |

## 4. 建議行動

1. **（docs-only 小任務，可登記）** 「本機 Ollama／OpenAI-compatible provider 配置 recipe」：驗證 `LOCAL_LOOPBACK` 揭露行為＋embedding 維度／sqlite-vec 相容性檢查＋CJK 模型建議。這是全離線能力的收尾文件，成本極低、與 LEANN 評估的 local-embedding 前提同一條路徑。
2. **（文件動作，local-only）** collection-manifest 模式記入 `.ai_llm_wiki_km` Proposed 池（公開語料匯入的形狀參照，標明 DEFER 與 trigger）。
3. **（docs 借鏡，可併入 1）** 硬體／資源信封段落。
4. **（不立項）** §3 全部；無 production 變更。

## 5. 殘留限制

* README／FAQ 層級核對；未實際安裝 NOMAD、未實測其 Qdrant RAG 的答案與 citation 行為（README 未宣稱 citation 治理，具體行為未知）。
* 其「AI Assistant」為 optional 模組——本評估未深查其 RAG 品質文件或 judge 機制。
* Ollama 的 `/v1` 相容性與本專案 transport 的實際互通（streaming、usage metadata、embedding API 形狀）未驗證——§2.1 的 recipe 文件應以實測收尾。
* 與既有評估的關係：本篇是「本地優先產品」定位的對照組（OpenResearch 是 harness、Hindsight 是記憶系統、NOMAD 是內容伺服器）；§2.1 與 LEANN 評估 §3.1／hindsight local-daemon pilot 共享「本機模型服務」前置條件。
