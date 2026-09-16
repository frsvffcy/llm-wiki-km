package org.km.llmwiki.acceptance;

import java.util.List;

/**
 * Versioned privacy-safe daily-workflow corpus v2 (Refs #467 §A).
 *
 * <p>Small, deterministic, synthetic fixture set. No real user vault content,
 * no secrets, no API keys, no absolute paths. All content is synthetic and
 * CJK-inclusive with exact-token / code-like / property-like anchors so
 * lexical retrieval can be proven without natural-language similarity.
 *
 * <p>Delta vs {@link ProductAcceptanceCorpusV1}: adds code-like /
 * property-like text fixture, heading/table structure-observable markdown,
 * a source revision pair (v1/v2) for re-extraction + stale/currentness, and
 * a no-answer probe that must never match any fixture.
 */
public final class DailyWorkflowCorpusV2 {

    public static final String VERSION = "daily-workflow-corpus-v2";
    public static final String PROCEDURE_VERSION = "daily-workflow-procedure-v2";

    /** Exact-token anchor shared by the cross-retrievable daily sources. */
    public static final String DAILY_ANCHOR = "DAILY_WORKFLOW_TOKEN_GAMMA_467";

    /** Property-like anchor carried by the structured + config fixtures. */
    public static final String PROPERTY_ANCHOR = "DAILY_WORKFLOW_PROPERTY_DELTA_467";

    /** Property-like key=value anchor (avoids natural-language-only measurement). */
    public static final String PROPERTY_LINE = "daily.workflow.owner=daily-workspace";

    /** Code-like anchor proving exact-token retrieval over non-prose content. */
    public static final String CODE_ANCHOR = "DailyWorkflowGamma.resolve()";

    /** Revision v1-only anchor: must disappear from serving after v2 re-extraction. */
    public static final String REVISION_ANCHOR_V1 = "DAILY_REVISION_ANCHOR_V1_467";

    /** Revision v2-only anchor: must be retrievable only after v2 re-extraction. */
    public static final String REVISION_ANCHOR_V2 = "DAILY_REVISION_ANCHOR_V2_467";

    /** Deterministic CJK probe whose bigrams are fully covered by the fixtures. */
    public static final String QUESTION = "日常工作區的檢索錨點";

    /**
     * No-answer probe: CJK question plus a token that appears in no fixture.
     * Retrieval must return zero candidates and Ask must return
     * {@code INSUFFICIENT_EVIDENCE} rather than a fabricated answer.
     */
    public static final String NO_ANSWER_QUESTION = "不存在於日常語料庫的虛構查詢 NO_EVIDENCE_TOKEN_ZETA_467_XYZ";

    /** Token guaranteed absent from every fixture (no-answer negative control). */
    public static final String NO_EVIDENCE_TOKEN = "NO_EVIDENCE_TOKEN_ZETA_467_XYZ";

    private DailyWorkflowCorpusV2() {
    }

    public record Fixture(String fileName, String contentType, String content) {
    }

    /**
     * Baseline daily-use fixtures (revision fixture ships v1; v2 is applied
     * mid-journey as a user file edit + rescan, never as a second committed file).
     */
    public static List<Fixture> fixtures() {
        return List.of(
                new Fixture("daily-guide.md", "text/markdown", guideMarkdown()),
                new Fixture("daily-runbook.md", "text/markdown", runbookMarkdown()),
                new Fixture("daily-config.properties", "text/plain", configProperties()),
                new Fixture("daily-structured.html", "text/html", structuredHtml()),
                new Fixture("daily-revision.md", "text/markdown", revisionV1()));
    }

    public static String guideMarkdown() {
        return """
                # 日常工作區使用指南

                本文件為 daily-workflow validation v2 專用的合成測試資料，不含任何真實使用者內容。

                ## 檢索錨點

                日常工作區的檢索錨點是 DAILY_WORKFLOW_TOKEN_GAMMA_467。

                日常屬性：daily.workflow.owner=daily-workspace

                程式錨點：DailyWorkflowGamma.resolve()

                ## 結構觀察

                | 欄位 | 值 |
                | --- | --- |
                | anchor | DAILY_WORKFLOW_TOKEN_GAMMA_467 |
                | owner | daily-workspace |

                ## 繁中內容

                知識管理系統應支援繁體中文全文檢索，包含斷詞與精確 token 匹配。
                """;
    }

    public static String runbookMarkdown() {
        return """
                # 日常工作區操作手冊

                本文件為第二份可形成跨文件檢索的合成來源。

                ## 交叉引用

                第二份來源確認檢索錨點仍為 DAILY_WORKFLOW_TOKEN_GAMMA_467。

                透過 HYBRID_FTS 應可同時檢索到指南與手冊的相關區塊。

                ## 操作摘要

                上傳後執行 extraction，確認 chunk 為最新版本且可經由檢視器定位。
                """;
    }

    /** Plain-text config exercising exact-token retrieval over non-prose content. */
    public static String configProperties() {
        return """
                # daily-workflow synthetic config (no secrets, no credentials)
                daily.workflow.owner=daily-workspace
                daily.workflow.anchor=DAILY_WORKFLOW_TOKEN_GAMMA_467
                daily.workflow.property=DAILY_WORKFLOW_PROPERTY_DELTA_467
                daily.workflow.entrypoint=DailyWorkflowGamma.resolve()
                """;
    }

    /**
     * Structured document exercising the production Tika parser path (headings +
     * table). Served as {@code text/html}; Tika production parser handles it
     * without any opaque committed binary.
     */
    public static String structuredHtml() {
        return """
                <!DOCTYPE html>
                <html lang="zh-Hant">
                <head><meta charset="utf-8"><title>日常結構化文件</title></head>
                <body>
                <h1>日常結構化文件</h1>
                <p>本文件以結構化 HTML 驗證 Tika production parser 路徑。</p>
                <h2>屬性錨點</h2>
                <p>結構化錨點為 DAILY_WORKFLOW_PROPERTY_DELTA_467。</p>
                <table>
                <tr><th>屬性</th><th>值</th></tr>
                <tr><td>anchor</td><td>DAILY_WORKFLOW_PROPERTY_DELTA_467</td></tr>
                <tr><td>owner</td><td>daily-workspace</td></tr>
                </table>
                <h2>檢索交叉</h2>
                <p>結構化文件亦提及 DAILY_WORKFLOW_TOKEN_GAMMA_467 以利跨文件檢索。</p>
                </body>
                </html>
                """;
    }

    /** Revision v1: initial user file content (contains V1-only anchor). */
    public static String revisionV1() {
        return """
                # 日常修訂文件

                本文件用於驗證 source revision / re-extraction 與 stale/currentness。

                ## 修訂錨點

                修訂版本一錨點為 DAILY_REVISION_ANCHOR_V1_467。

                日常工作區的檢索錨點是 DAILY_WORKFLOW_TOKEN_GAMMA_467。
                """;
    }

    /**
     * Revision v2: user-edited replacement content. Shares the daily anchor so
     * the document stays discoverable, drops the V1 anchor, adds the V2 anchor.
     * Applied mid-journey as a filesystem edit + rescan, never committed as a
     * second fixture file.
     */
    public static String revisionV2() {
        return """
                # 日常修訂文件

                本文件已由使用者修訂為第二版，用於驗證重新抽取後的 currentness。

                ## 修訂錨點

                修訂版本二錨點為 DAILY_REVISION_ANCHOR_V2_467。

                日常工作區的檢索錨點是 DAILY_WORKFLOW_TOKEN_GAMMA_467。
                """;
    }

    /** Application-owned expected facts (identities described, never raw secrets). */
    public static List<String> expectedFacts() {
        return List.of(
                "retrieval anchor token: " + DAILY_ANCHOR,
                "property anchor: " + PROPERTY_ANCHOR,
                "property line: " + PROPERTY_LINE,
                "code anchor: " + CODE_ANCHOR,
                "revision v1 anchor: " + REVISION_ANCHOR_V1,
                "revision v2 anchor: " + REVISION_ANCHOR_V2,
                "no-evidence token (must never match): " + NO_EVIDENCE_TOKEN,
                "structured fixture parsed via production Tika path");
    }
}
