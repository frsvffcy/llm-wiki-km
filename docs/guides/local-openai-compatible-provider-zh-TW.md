# 使用本機 OpenAI-compatible Provider（Ollama 範例）

> 狀態：`CURRENT`。這份指南說明如何把 llm-wiki-km 的 Answer／Embedding provider 指向**同一台電腦上的 OpenAI-compatible endpoint**，讓提問流程不必把問題送到外部雲端。
>
> 這是設定指南，不是新的 runtime contract。實際設定鍵以 `src/main/resources/application.yml` 與目前 provider adapter 為準。

## 1. 先知道這件事的重點

llm-wiki-km 本來就支援 OpenAI-compatible provider。若 provider 跑在本機，例如：

```text
llm-wiki-km
    ↓
http://127.0.0.1:11434/v1
    ↓
Ollama 或其他 OpenAI-compatible server
```

後端會把它分類為 `LOCAL_LOOPBACK`。

這表示：

- 瀏覽器仍只連 llm-wiki-km；
- provider key 仍只存在後端環境變數；
- `http://127.0.0.1`／`http://localhost` 屬 loopback，可直接使用 HTTP；
- **不需要**把 `ANSWER_PROVIDER_ALLOW_INSECURE_TRANSPORT` 設為 `true`；
- 不應為了方便，把遠端 HTTP endpoint 假裝成本機 endpoint。

本機模型的品質、速度、RAM／GPU 需求與繁中能力會因模型與硬體而不同。本專案不替你保證特定模型品質。

## 2. 準備本機 Provider

以下以 Ollama 為例。先確認 Ollama 已啟動，並列出目前已安裝的模型：

```bash
ollama list
```

確認 OpenAI-compatible endpoint 可回應：

```bash
curl http://127.0.0.1:11434/v1/models
```

若這一步失敗，先處理 Ollama 本身；不要先修改 llm-wiki-km。

## 3. 最小設定：只把 Answer 放在本機

先從 `ollama list` 選一個**你已經安裝**的聊天模型，將下面的 `YOUR_INSTALLED_MODEL` 改成那個名稱：

```bash
export ANSWER_PROVIDER_ENABLED=true
export ANSWER_PROVIDER=openai-compatible
export ANSWER_PROVIDER_BASE_URL=http://127.0.0.1:11434/v1
export ANSWER_PROVIDER_MODEL='YOUR_INSTALLED_MODEL'
read -r -s -p "本機 Provider credential（若本機 server 不驗證，可輸入 local-only）: " LOCAL_PROVIDER_KEY
echo
export OPENAI_API_KEY="$LOCAL_PROVIDER_KEY"
```

然後啟動 llm-wiki-km：

```bash
mvn spring-boot:run
```

`OPENAI_API_KEY` 必須是非空白值。若你的本機 provider 不驗證 Bearer token，可在上面的互動提示輸入 `local-only` 之類的非秘密 placeholder；若 provider 真的要求認證，就輸入它要求的 credential。這個值只存在目前 shell 環境，**不要把真正的 API key 寫進 Git。**

llm-wiki-km 會在 base URL 後使用 OpenAI-compatible 的：

```text
POST /chat/completions
```

而且 request 會使用 JSON response format。若你的本機 server 雖然宣稱 OpenAI-compatible，卻不支援這個契約，Ask 仍可能失敗；這屬 provider 相容性問題，不應繞過 response contract。

## 4. 確認 llm-wiki-km 真的把它視為本機 Provider

啟動後執行：

```bash
curl http://127.0.0.1:8765/api/v1/system/ai-provider-egress
```

Answer provider 應顯示等價於：

```text
purpose = ANSWER
destinationClass = LOCAL_LOOPBACK
```

這個 API 不會回傳完整 endpoint 或 credential。

若看到 `UNAVAILABLE_OR_INVALID`，先檢查：

1. `ANSWER_PROVIDER_BASE_URL` 是否真的是 `http://127.0.0.1:.../v1` 或 `http://localhost:.../v1`；
2. 是否誤填成無效 URL；
3. 模型名稱是否為空白；
4. `OPENAI_API_KEY` 是否為空白。

不要為了解決本機設定問題，把遠端 HTTP 的 insecure opt-in 打開。

## 5. 用 Browser 做最簡單 smoke check

瀏覽器開啟：

```text
http://127.0.0.1:8765/
```

然後：

1. 到「管理文件」確認至少有一份文件顯示「可以開始使用」；
2. 從該文件按「開始提問」；
3. 問一個答案明確存在於文件內的問題；
4. 確認有正常回答與引用；
5. 再到「更多」中的檢視器確認來源定位合理。

若 provider 能回答、但引用／structured response 不符合 llm-wiki-km contract，後端會 fail closed；不要把驗證關掉來換取表面成功。

## 6. 可選：Embedding 也放在本機

只有在你真的需要 semantic/vector retrieval 時才設定。先確認你的本機 provider 支援 OpenAI-compatible：

```text
POST /embeddings
```

設定範例：

```bash
export EMBEDDING_PROVIDER_ENABLED=true
export EMBEDDING_PROVIDER=openai-compatible
export EMBEDDING_PROVIDER_BASE_URL=http://127.0.0.1:11434/v1
export EMBEDDING_PROVIDER_MODEL='YOUR_INSTALLED_EMBEDDING_MODEL'
export EMBEDDING_PROVIDER_API_KEY="$LOCAL_PROVIDER_KEY"
```

`EMBEDDING_PROVIDER_DIMENSION` 預設為 `0`，代表 adapter 接受 provider 回傳的 bounded dimension；若設為正整數，回傳向量維度必須完全相同，否則 fail closed。

重要限制：

- 同一套向量投影應維持固定的 embedding model／dimension；
- 更換 embedding model 或 dimension 後，應依現行 vector projection 流程重建，不要混用舊向量；
- 不要因為某個模型在外部基準測試排名高，就直接假設它適合繁中個人知識庫；
- 模型品質應以自己的 corpus、Recall/MRR、延遲與資源成本量測。

啟用後可查看：

```bash
curl http://127.0.0.1:8765/api/v1/semantic/health
curl http://127.0.0.1:8765/api/v1/system/ai-provider-egress
```

Embedding provider 也應被分類為 `LOCAL_LOOPBACK`。

## 7. 常見錯誤

| 情況 | 最可能原因 | 怎麼做 |
| --- | --- | --- |
| Provider 顯示 disabled | 對應 `*_PROVIDER_ENABLED` 沒開 | 確認環境變數後重啟 |
| Provider 顯示 invalid | base URL 格式錯誤或 transport policy 不接受 | 使用真正的 loopback URL，例如 `http://127.0.0.1:11434/v1` |
| 401／403 | 本機 server 真的要求認證 | 依 provider 規則設定 credential；不要把 key 寫入 repo |
| 404 | OpenAI-compatible path 不存在 | 確認 server 支援 `/v1/chat/completions` 或 `/v1/embeddings` |
| 回答格式錯誤 | provider 不支援目前 JSON response contract | 換相容模型／server；不要關閉驗證 |
| Embedding 維度錯誤 | 設定 dimension 與 provider 回傳不一致 | 修正 dimension，必要時重建 vector projection |
| 很慢或記憶體不足 | 模型超過本機資源能力 | 換較小模型或只保留 Answer 本機化；不要把延遲問題誤認成 retrieval bug |

## 8. 安全與隱私邊界

「本機 Provider」只代表 provider endpoint 位於 loopback，**不代表所有其他功能都自動離線**。

請仍確認：

- Query rewrite provider 是否保持 disabled；
- 文件分析若另有 provider 設定，是否仍指向遠端；
- 任何未來新增的外部 connector／provider 都要個別看 egress disclosure；
- Browser 不應直接取得任何 provider credential。

可隨時用：

```bash
curl http://127.0.0.1:8765/api/v1/system/ai-provider-egress
```

檢查目前 Answer／Embedding／Query Rewrite 各自的 destination classification。

## 9. 最小結論

若你的目標只是「Ask 不要把問題送到外部雲端」，最小做法就是：

```text
本機啟動 OpenAI-compatible server
→ ANSWER_PROVIDER_BASE_URL 指向 127.0.0.1
→ 使用已安裝模型
→ 啟動 llm-wiki-km
→ 確認 ai-provider-egress = LOCAL_LOOPBACK
→ 從 Browser 用一份 READY_TO_USE 文件實際提問
```

不需要更換 llm-wiki-km 架構，也不需要新增 Ollama dependency。
