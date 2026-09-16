package org.km.llmwiki.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Static-asset URL fingerprinting contract (Refs #481).
 *
 * <p>Browser-level evidence (persistent profile, pre-#479 JAR to post-#479 JAR on one
 * origin) proves #479's response headers alone cannot heal an upgraded session:
 * plain navigation serves the old document and every old subresource from disk with
 * zero requests, and normal reload revalidates only the top document while
 * heuristically fresh subresources are reused without any request — so the old
 * {@code workspace-ui.js} keeps executing and the workspace list never loads.
 * A request the Browser never sends cannot be answered with new bytes; the URLs
 * themselves must therefore change whenever their bytes change.
 *
 * <p>This suite is the executable equivalent of that browser lifecycle gate: it pins
 * that every script/stylesheet referenced by the served document carries a token
 * that is a pure function of the served bytes (any content change necessarily
 * changes the URL, forcing a compulsory cache miss after the document revalidates),
 * and that the versioned URL serves byte-identical content under the identical #479
 * cache contract (query ignored by resource resolution, ETag filter, and CSP).
 */
@Tag("integration")
class StaticAssetVersionIntegrationTest extends IsolatedIntegrationTest {

    private static final Pattern VERSIONED_REF =
            Pattern.compile("(?:src|href)=\"(?<url>/(?<file>[A-Za-z0-9._\\-/]+\\.(?:js|css))\\?v=(?<token>[0-9a-f]{12}))\"");
    private static final Pattern BARE_REF =
            Pattern.compile("(?:src|href)=\"/(?<file>[A-Za-z0-9._\\-/]+\\.(?:js|css))\"");

    @Autowired
    private MockMvc mvc;

    @Test
    void everyServedScriptAndStylesheetCarriesItsContentHash() throws Exception {
        // NOTE: the body is read from /index.html (not the / welcome-page mapping:
        // MockMvc does not follow the welcome-page forward, so / exposes headers only).
        // The / and /index.html equivalence (same bytes, same validators) is pinned by
        // StaticAssetCacheIntegrationTest; packaged-app curl evidence confirms it.
        MvcResult index = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn();
        String html = index.getResponse().getContentAsString();

        Matcher bare = BARE_REF.matcher(html);
        assertThat(bare.find())
                .as("served document must not keep bare local script/stylesheet references")
                .isFalse();

        Map<String, String> tokens = new LinkedHashMap<>();
        Matcher versioned = VERSIONED_REF.matcher(html);
        while (versioned.find()) {
            tokens.put("/" + versioned.group("file"), versioned.group("token"));
        }
        assertThat(tokens)
                .as("served document must reference versioned scripts and stylesheet")
                .hasSizeGreaterThanOrEqualTo(13);
        assertThat(tokens).containsKeys("/workspace-ui.js", "/styles.css", "/navigation-ui.js");

        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            MvcResult asset = mvc.perform(get(entry.getKey()))
                    .andExpect(status().isOk())
                    .andReturn();
            byte[] body = asset.getResponse().getContentAsByteArray();
            assertThat(body).as("versioned asset %s must serve bytes", entry.getKey()).isNotEmpty();
            assertThat(contentToken(body))
                    .as("token for %s must equal the content hash (content change => URL change)", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void versionedUrlServesIdenticalBytesUnderTheSameCacheContract() throws Exception {
        MvcResult index = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn();
        Matcher versioned = VERSIONED_REF.matcher(index.getResponse().getContentAsString());
        String versionedUrl = null;
        while (versioned.find()) {
            if (versioned.group("file").equals("workspace-ui.js")) {
                versionedUrl = versioned.group("url");
            }
        }
        assertThat(versionedUrl).as("workspace-ui.js must be versioned").isNotNull();

        MvcResult bare = mvc.perform(get("/workspace-ui.js"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult stamped = mvc.perform(get(versionedUrl))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(stamped.getResponse().getContentAsByteArray())
                .as("versioned URL must serve byte-identical content (query ignored)")
                .isEqualTo(bare.getResponse().getContentAsByteArray());

        String cacheControl = stamped.getResponse().getHeader("Cache-Control");
        assertThat(cacheControl).contains("no-cache");
        assertThat(cacheControl).doesNotContain("no-store");
        assertThat(stamped.getResponse().getHeader("ETag"))
                .as("versioned URL keeps the #479 content ETag")
                .isEqualTo(bare.getResponse().getHeader("ETag"));
        assertThat(stamped.getResponse().getHeader("Last-Modified")).isNull();

        String etag = stamped.getResponse().getHeader("ETag");
        mvc.perform(get(versionedUrl).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    private static String contentToken(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).substring(0, 12);
    }
}
