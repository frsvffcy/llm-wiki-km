/** 驗證後端經內容雜湊確認的已發布 Wiki 頁面 DTO 最低必要欄位。 */
export function isPublishedWikiPage(data, knowledgeId) {
  return data !== null && typeof data === "object" && !Array.isArray(data)
    && Number.isInteger(data.id) && data.id > 0
    && typeof data.knowledgeId === "string" && data.knowledgeId === String(knowledgeId)
    && typeof data.title === "string"
    && typeof data.pageType === "string"
    && Number.isInteger(data.revision) && data.revision >= 1
    && typeof data.contentHash === "string"
    && typeof data.updatedAt === "string"
    && typeof data.markdown === "string";
}
