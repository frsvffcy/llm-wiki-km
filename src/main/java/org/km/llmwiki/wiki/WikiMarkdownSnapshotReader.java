package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Read-only Markdown snapshot loader; it never creates or modifies a Wiki file. */
@Component
public class WikiMarkdownSnapshotReader {

    private final ActiveWorkspaceWikiPathResolver pathResolver;

    public WikiMarkdownSnapshotReader(ActiveWorkspaceWikiPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    public WikiTargetBaseline capture(WikiTargetSnapshot target) {
        Path path = pathResolver.resolveAndValidateRealPath(target.logicalRelativePath());
        return switch (target.kind()) {
            case CREATE_NEW -> captureCreate(path);
            case EXISTING -> captureExisting(path, target.currentContentHash());
        };
    }

    private static WikiTargetBaseline captureCreate(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WikiDraftTargetException(WikiDraftTargetException.Reason.CREATE_TARGET_EXISTS,
                    "CREATE target already exists in the active vault");
        }
        return new WikiTargetBaseline("", WikiContentHash.sha256(""));
    }

    private static WikiTargetBaseline captureExisting(Path path, String expectedHash) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WikiDraftTargetException(WikiDraftTargetException.Reason.TARGET_FILE_MISSING,
                    "MERGE target file does not exist in the active vault");
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WikiDraftTargetException(WikiDraftTargetException.Reason.TARGET_NOT_REGULAR_FILE,
                    "MERGE target must be a regular non-symlink Markdown file");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            String actualHash = WikiContentHash.sha256(bytes);
            if (!actualHash.equals(expectedHash)) {
                throw new WikiDraftTargetException(WikiDraftTargetException.Reason.TARGET_CONTENT_HASH_MISMATCH,
                        "MERGE target content does not match the #90 expected content hash");
            }
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return new WikiTargetBaseline(normalizeMarkdown(decoded), actualHash);
        } catch (CharacterCodingException exception) {
            throw new WikiDraftTargetException(WikiDraftTargetException.Reason.TARGET_CONTENT_INVALID,
                    "MERGE target is not valid UTF-8 Markdown", exception);
        } catch (IOException exception) {
            throw new WikiDraftTargetException(WikiDraftTargetException.Reason.TARGET_CONTENT_INVALID,
                    "MERGE target content could not be read", exception);
        }
    }

    private static String normalizeMarkdown(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.startsWith("\ufeff") ? normalized.substring(1) : normalized;
    }
}
