package org.km.llmwiki.acceptance;

import java.util.List;

/**
 * Versioned golden-workspace acceptance corpus (Refs #429 §A).
 *
 * <p>Small, deterministic, privacy-safe fixture set. No real user vault content,
 * no secrets, no API keys, no absolute paths. All content is synthetic and
 * CJK-inclusive with exact-token / property-like anchors so lexical retrieval
 * can be proven without relying on natural-language similarity.
 */
public final class ProductAcceptanceCorpusV1 {

    public static final String VERSION = "product-acceptance-corpus-v1";
    public static final String PROCEDURE_VERSION = "product-acceptance-procedure-v1";

    /** Exact-token anchor shared by both markdown sources (lexical citation proof). */
    public static final String ANCHOR_TOKEN = "ACCEPTANCE_TOKEN_ALPHA_429";

    /** Property-like anchor carried by the structured Tika fixture. */
    public static final String PROPERTY_ANCHOR = "ACCEPTANCE_PROPERTY_BETA_429";

    /** Property-like key=value anchor (avoids natural-language-only measurement). */
    public static final String PROPERTY_LINE = "acceptance.property.owner=golden-workspace";

    /** Deterministic CJK probe whose bigrams are fully covered by the fixtures. */
    public static final String QUESTION = "金鑰工作區的檢索錨點";

    private ProductAcceptanceCorpusV1() {
    }

    public record Fixture(String fileName, String contentType, String content) {
    }

    /** Two cross-retrievable markdown sources plus one Tika-structured document. */
    public static List<Fixture> fixtures() {
        return List.of(
                new Fixture("acceptance-guide.md", "text/markdown", guideMarkdown()),
                new Fixture("acceptance-runbook.md", "text/markdown", runbookMarkdown()),
                new Fixture("acceptance-structured.html", "text/html", structuredHtml()));
    }

    public static String guideMarkdown() {
        return """
                # 金鑰工作區驗收指南

                本文件為 v0.1.0 產品驗收專用的合成測試資料，不含任何真實使用者內容。

                ## 檢索錨點

                金鑰工作區的檢索錨點是 ACCEPTANCE_TOKEN_ALPHA_429。

                驗收屬性：acceptance.property.owner=golden-workspace

                ## 繁中內容

                知識管理系統應支援繁體中文全文檢索，包含斷詞與精確 token 匹配。
                """;
    }

    public static String runbookMarkdown() {
        return """
                # 金鑰工作區驗收手冊

                本文件為第二份可形成跨文件檢索的合成來源。

                ## 交叉引用

                第二份來源確認檢索錨點仍為 ACCEPTANCE_TOKEN_ALPHA_429。

                透過 HYBRID_FTS 應可同時檢索到指南與手冊的相關區塊。

                ## 操作摘要

                上傳後執行 extraction，確認 chunk 為最新版本且可經由檢視器定位。
                """;
    }

    /**
     * Structured document exercising the production Tika parser path (headings +
     * table). Served as {@code text/html}; Tika production parser handles it
     * without any opaque committed binary (Refs #429 §A).
     */
    public static String structuredHtml() {
        return """
                <!DOCTYPE html>
                <html lang="zh-Hant">
                <head><meta charset="utf-8"><title>驗收結構化文件</title></head>
                <body>
                <h1>驗收結構化文件</h1>
                <p>本文件以結構化 HTML 驗證 Tika production parser 路徑。</p>
                <h2>屬性錨點</h2>
                <p>結構化錨點為 ACCEPTANCE_PROPERTY_BETA_429。</p>
                <table>
                <tr><th>屬性</th><th>值</th></tr>
                <tr><td>anchor</td><td>ACCEPTANCE_PROPERTY_BETA_429</td></tr>
                <tr><td>owner</td><td>golden-workspace</td></tr>
                </table>
                <h2>檢索交叉</h2>
                <p>結構化文件亦提及 ACCEPTANCE_TOKEN_ALPHA_429 以利跨文件檢索。</p>
                </body>
                </html>
                """;
    }

    /** Application-owned expected facts (identities described, never raw secrets). */
    public static List<String> expectedFacts() {
        return List.of(
                "retrieval anchor token: " + ANCHOR_TOKEN,
                "property anchor: " + PROPERTY_ANCHOR,
                "property line: " + PROPERTY_LINE,
                "structured fixture parsed via production Tika path");
    }
}
