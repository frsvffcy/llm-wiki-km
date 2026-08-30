package org.km.llmwiki.wiki;

import org.km.llmwiki.workspace.WorkspaceRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;

/**
 * Reads Published Wiki content through durable metadata and the canonical path authority.
 *
 * <p>Callers never provide a filesystem path. The vault bytes must match the authoritative
 * {@code knowledge_page.content_hash} before content can leave this boundary.
 */
@Component
public class PublishedWikiContentReader {

    private static final String FRONTMATTER_SEPARATOR = "\n---\n\n";

    private final WorkspaceRepository workspaceRepository;
    private final WikiPathContract pathContract;

    public PublishedWikiContentReader(WorkspaceRepository workspaceRepository,
                                      WikiPathContract pathContract) {
        this.workspaceRepository = workspaceRepository;
        this.pathContract = pathContract;
    }

    public String readSearchableContent(StoredPublishedWiki page) {
        Path target = resolveTarget(page);
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Published Wiki target must be a regular non-symlink file");
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            if (!WikiContentHash.sha256(bytes).equals(page.contentHash())) {
                throw new IllegalStateException(
                        "Vault Markdown hash differs from knowledge_page.content_hash");
            }
            String markdown = decodeUtf8(bytes);
            validateCanonicalMarkdown(page, markdown);
            return searchableProjection(markdown);
        } catch (IOException exception) {
            throw new IllegalStateException("Published Wiki Markdown could not be read", exception);
        }
    }

    private Path resolveTarget(StoredPublishedWiki page) {
        var workspace = workspaceRepository.findById(page.workspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Published Wiki workspace was not found"));
        WikiPageType pathType = pathContract.validateLogicalPath(page.markdownPath());
        if (pathType != page.pageType()
                || !pathContract.resolveLogicalPath(page.pageType(), page.title())
                .equals(page.markdownPath())) {
            throw new IllegalStateException("Published Wiki metadata path is not canonical");
        }
        return pathContract.resolveAndValidateRealPath(
                Path.of(workspace.vaultPath()), page.markdownPath());
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void validateCanonicalMarkdown(StoredPublishedWiki page, String markdown) {
        if (!markdown.startsWith("---\n") || !markdown.contains(FRONTMATTER_SEPARATOR)) {
            throw new IllegalStateException(
                    "Published Wiki Markdown has no complete frontmatter block");
        }
        requireFrontmatter(markdown, "id", quote(page.knowledgeId()));
        requireFrontmatter(markdown, "title", quote(page.title()));
        requireFrontmatter(markdown, "type", quote(page.pageType().name()));
        requireFrontmatter(markdown, "status", quote(PageStatus.PUBLISHED.name()));
        if (!markdown.contains("\n# " + page.title() + "\n")) {
            throw new IllegalStateException(
                    "Published Wiki Markdown title does not match metadata");
        }
    }

    private static String searchableProjection(String markdown) {
        int separator = markdown.indexOf(FRONTMATTER_SEPARATOR);
        if (separator < 0 || separator + FRONTMATTER_SEPARATOR.length() >= markdown.length()) {
            throw new IllegalStateException("Published Wiki Markdown has no searchable body");
        }
        String body = markdown.substring(separator + FRONTMATTER_SEPARATOR.length());
        return Normalizer.normalize(body, Normalizer.Form.NFC);
    }

    private static void requireFrontmatter(String markdown, String name, String value) {
        String expected = name + ": " + value;
        if (markdown.lines().noneMatch(line -> line.equals(expected))) {
            throw new IllegalStateException(
                    "Published Wiki frontmatter field does not match " + name);
        }
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + '"';
    }
}
