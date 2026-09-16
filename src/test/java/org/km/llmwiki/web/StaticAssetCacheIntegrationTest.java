package org.km.llmwiki.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Static-asset cache currentness contract (Refs #479).
 *
 * <p>Proves an upgraded JAR is picked up by an existing Browser session on
 * plain refresh: static HTML/JS/CSS are stored-but-revalidated
 * ({@code no-cache}), validated by content {@code ETag} only (no pinned
 * {@code Last-Modified}), so changed bytes return {@code 200} and unchanged
 * bytes return {@code 304}. A stale {@code If-Modified-Since} alone must never
 * yield {@code 304}.
 */
@Tag("integration")
class StaticAssetCacheIntegrationTest extends IsolatedIntegrationTest {

    private static final String PINNED_OUTPUT_TIMESTAMP = "Tue, 15 Sep 2026 00:00:00 GMT";

    @Autowired
    private MockMvc mvc;

    @Test
    void workspaceJsIsRevalidatedByContentEtagWithoutTimestampValidator() throws Exception {
        MvcResult first = mvc.perform(get("/workspace-ui.js"))
                .andExpect(status().isOk())
                .andReturn();

        String cacheControl = first.getResponse().getHeader("Cache-Control");
        assertThat(cacheControl).contains("no-cache");
        assertThat(cacheControl).doesNotContain("no-store");

        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        assertThat(first.getResponse().getHeader("Last-Modified")).isNull();

        // Unchanged bytes revalidate to 304 via the content ETag.
        mvc.perform(get("/workspace-ui.js").header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        // The pre-#479 stale validator (pinned reproducible-build timestamp)
        // must never produce a false 304 on its own.
        mvc.perform(get("/workspace-ui.js").header("If-Modified-Since", PINNED_OUTPUT_TIMESTAMP))
                .andExpect(status().isOk());
    }

    @Test
    void indexHtmlAndWelcomePageShareTheSameRevalidationContract() throws Exception {
        for (String path : new String[]{"/index.html", "/", "/styles.css"}) {
            MvcResult first = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn();

            String cacheControl = first.getResponse().getHeader("Cache-Control");
            assertThat(cacheControl)
                    .as("Cache-Control for %s", path)
                    .contains("no-cache");
            assertThat(cacheControl).doesNotContain("no-store");

            String etag = first.getResponse().getHeader("ETag");
            assertThat(etag).as("ETag for %s", path).isNotBlank();
            assertThat(first.getResponse().getHeader("Last-Modified"))
                    .as("Last-Modified for %s", path)
                    .isNull();

            mvc.perform(get(path).header("If-None-Match", etag))
                    .andExpect(status().isNotModified());
            mvc.perform(get(path).header("If-Modified-Since", PINNED_OUTPUT_TIMESTAMP))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void apiResponsesAreNotGivenStaticAssetEtags() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getHeader("ETag")).isNull();
    }
}
