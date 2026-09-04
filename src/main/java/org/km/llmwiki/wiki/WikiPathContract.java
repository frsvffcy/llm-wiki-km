package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Enforces the vault path contract for Wiki pages.
 *
 * <p>This component is the <em>only</em> place that translates a (type, title) pair
 * into a filesystem path.  No other class should build paths into {@code vault/}
 * directly.  The following invariants are always enforced:
 *
 * <ul>
 *   <li>Titles are normalized to safe, deterministic filenames using Unicode NFC and {@link Locale#ROOT}.</li>
 *   <li>Resulting paths are workspace-relative logical strings (no leading slash).</li>
 *   <li>Paths must not contain {@code ..} segments.</li>
 *   <li>Paths must belong to a controlled {@link WikiPageType} sub-directory.</li>
 *   <li>After real-path resolution the path must reside strictly inside the
 *       workspace {@code vault/} directory – symlink escapes are rejected.</li>
 * </ul>
 *
 * <p>This class has <strong>no side effects</strong> on the filesystem:
 * it never creates, reads, or modifies any file or directory.
 */
@Component
public class WikiPathContract {

    /**
     * Maximum length (in characters) of a normalized filename stem
     * (without the {@code .md} extension).  Prevents excessively long filenames
     * that could exceed filesystem limits on some operating systems.
     */
    static final int MAX_STEM_LENGTH = 200;

    /**
     * Normalizes a Wiki page title to a safe, deterministic Markdown filename.
     *
     * <p>Normalization rules (applied in order):
     * <ol>
     *   <li>Strip leading/trailing whitespace.</li>
     *   <li>Normalize Unicode characters to Canonical Composition (NFC).</li>
     *   <li>Convert to lowercase using {@link Locale#ROOT} to ensure locale-independent results.</li>
     *   <li>Replace one or more consecutive whitespace characters with a single hyphen.</li>
     *   <li>Remove any character that is not alphanumeric, a CJK/Unicode letter, a hyphen, or an underscore.</li>
     *   <li>Collapse consecutive hyphens to a single hyphen.</li>
     *   <li>Strip leading and trailing hyphens.</li>
     *   <li>Truncate the stem to {@value #MAX_STEM_LENGTH} characters.</li>
     *   <li>Append {@code .md} extension.</li>
     * </ol>
     *
     * @param title the page title (e.g. {@code "Spring Boot 3 Migration"})
     * @return a safe filename like {@code "spring-boot-3-migration.md"}
     * @throws WikiPathValidationException if the title is null, blank, or normalizes to an empty stem
     */
    public String normalizeTitleToFileName(String title) {
        if (title == null || title.isBlank()) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.INVALID_TITLE,
                    "Wiki page title must not be null or blank");
        }

        // 1. Strip surrounding whitespace
        String stem = title.strip();

        // 2. Unicode Canonical Composition (NFC)
        stem = Normalizer.normalize(stem, Normalizer.Form.NFC);

        // 3. Locale-independent lowercase
        stem = stem.toLowerCase(Locale.ROOT);

        // 4. Replace whitespace runs with hyphen
        stem = stem.replaceAll("\\s+", "-");

        // 5. Remove unsafe characters; keep Unicode letters/digits, hyphen, underscore
        stem = stem.replaceAll("[^\\p{L}\\p{N}\\-_]", "");

        // 6. Collapse consecutive hyphens
        stem = stem.replaceAll("-{2,}", "-");

        // 7. Strip leading/trailing hyphens
        stem = stem.replaceAll("^-+|-+$", "");

        if (stem.isEmpty()) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.INVALID_TITLE,
                    "Wiki page title '" + title + "' cannot be normalized to a valid filename");
        }

        // 8. Truncate
        if (stem.length() > MAX_STEM_LENGTH) {
            stem = stem.substring(0, MAX_STEM_LENGTH);
            // Re-strip trailing hyphens that may have been exposed after truncation
            stem = stem.replaceAll("-+$", "");
        }

        if (stem.isEmpty()) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.INVALID_TITLE,
                    "Wiki page title '" + title + "' cannot be normalized to a valid filename after truncation");
        }

        // 9. Append extension
        return stem + ".md";
    }

    /**
     * Builds a workspace-relative logical path for a Wiki page.
     *
     * <p>The path takes the form {@code vault/<folderName>/<filename>}.
     * No leading slash is included; the path is always relative to the workspace root.
     *
     * @param type  the controlled page type
     * @param title the page title
     * @return a logical relative path like {@code "vault/concepts/spring-boot-3.md"}
     * @throws WikiPathValidationException if the title cannot be normalized
     */
    public String resolveLogicalPath(WikiPageType type, String title) {
        if (type == null) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE,
                    "WikiPageType must not be null");
        }
        String fileName = normalizeTitleToFileName(title);
        return "vault/" + type.folderName() + "/" + fileName;
    }

    /**
     * Validates that a workspace-relative logical path is structurally safe and belongs to a
     * controlled {@link WikiPageType} folder.
     *
     * <p>This performs a <em>lexical</em> check only — it does not access the filesystem.
     * For real-path boundary enforcement, use
     * {@link #resolveAndValidateRealPath(Path, String)} instead.
     *
     * @param logicalRelativePath the workspace-relative path to validate
     * @return the resolved {@link WikiPageType} associated with the path's sub-directory
     * @throws WikiPathValidationException if the path violates any structural rule or has an uncontrolled folder
     */
    public WikiPageType validateLogicalPath(String logicalRelativePath) {
        if (logicalRelativePath == null || logicalRelativePath.isBlank()) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.INVALID_TITLE,
                    "Logical path must not be null or blank");
        }

        // Must not be absolute
        if (logicalRelativePath.startsWith("/") || logicalRelativePath.startsWith("\\")) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.ABSOLUTE_PATH_NOT_ALLOWED,
                    "Wiki path must be workspace-relative (no leading slash): " + logicalRelativePath);
        }

        // Must not contain traversal sequences
        Path parsed;
        try {
            parsed = Path.of(logicalRelativePath);
        } catch (InvalidPathException e) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.PATH_TRAVERSAL_DETECTED,
                    "Wiki path contains invalid characters: " + logicalRelativePath, e);
        }

        for (Path segment : parsed) {
            if ("..".equals(segment.toString())) {
                throw new WikiPathValidationException(
                        WikiPathValidationException.Reason.PATH_TRAVERSAL_DETECTED,
                        "Wiki path must not contain '..' segments: " + logicalRelativePath);
            }
        }

        // Must be under vault/
        if (!logicalRelativePath.startsWith("vault/")) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY,
                    "Wiki path must start with 'vault/': " + logicalRelativePath);
        }

        // Path structure must be exactly: vault/<folderName>/<filename>.md (3 segments)
        if (parsed.getNameCount() != 3) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY,
                    "Wiki path must have exactly 3 segments (vault/<type-folder>/<file>.md): " + logicalRelativePath);
        }

        String folderName = parsed.getName(1).toString();
        String fileName = parsed.getName(2).toString();

        if (!fileName.endsWith(".md")) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.INVALID_TITLE,
                    "Wiki filename must end with '.md': " + logicalRelativePath);
        }

        // Ensure folderName is a controlled WikiPageType
        return WikiPageType.fromFolderName(folderName);
    }

    /**
     * Resolves a workspace-relative logical path to an absolute filesystem path,
     * then verifies via {@link Path#toRealPath()} that the resolved path
     * lies strictly inside {@code vaultRoot}.
     *
     * <p>This method <strong>requires</strong> that the target file's parent
     * directory already exists (so that {@code toRealPath()} can resolve symlinks
     * on the parent chain).  The target file itself need not exist yet.
     *
     * @param vaultRoot           the absolute, canonical path to the workspace {@code vault/} directory
     * @param logicalRelativePath the workspace-relative path (e.g. {@code "vault/concepts/foo.md"})
     * @return the resolved, boundary-verified absolute path
     * @throws WikiPathValidationException if the resolved path would escape the vault boundary,
     *                                     or if the parent directory cannot be resolved
     */
    public Path resolveAndValidateRealPath(Path vaultRoot, String logicalRelativePath) {
        validateLogicalPath(logicalRelativePath);

        // Strip the leading "vault/" prefix — the caller already provides the vault root
        String pathUnderVault = logicalRelativePath.substring("vault/".length());

        Path targetAbsolute = vaultRoot.resolve(pathUnderVault).normalize();

        // Resolve the real path of the parent directory (which must exist)
        Path parentDir = targetAbsolute.getParent();
        if (parentDir == null) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY,
                    "Resolved path has no parent directory: " + targetAbsolute);
        }

        Path realParent;
        try {
            realParent = parentDir.toRealPath();
        } catch (IOException e) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY,
                    "Cannot resolve real path of parent directory '" + parentDir
                            + "'; ensure the vault sub-directory exists before publishing",
                    e);
        }

        Path realVaultRoot;
        try {
            realVaultRoot = vaultRoot.toRealPath();
        } catch (IOException e) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY,
                    "Cannot resolve real path of vault root: " + vaultRoot, e);
        }

        // The real parent must start with the real vault root
        if (!realParent.startsWith(realVaultRoot)) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.SYMLINK_ESCAPE,
                    "Resolved path '" + realParent + "' is outside the vault boundary '"
                            + realVaultRoot + "'. Possible symlink escape detected.");
        }

        return realParent.resolve(targetAbsolute.getFileName());
    }

    /**
     * Checks whether the vault sub-directory for the given page type already exists
     * under {@code vaultRoot}.  This is informational only — callers are responsible
     * for creating missing directories before writing.
     *
     * @param vaultRoot the workspace vault root
     * @param type      the page type
     * @return {@code true} if the sub-directory exists and is a directory
     */
    public boolean subDirectoryExists(Path vaultRoot, WikiPageType type) {
        return Files.isDirectory(vaultRoot.resolve(type.folderName()));
    }
}
