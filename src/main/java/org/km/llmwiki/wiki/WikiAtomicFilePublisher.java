package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Same-directory temp, fsync, validate, and atomic no-replace commit boundary. */
@Component
public class WikiAtomicFilePublisher {

    private final AtomicWikiFileCommitter committer;
    private final WikiPublishedMarkdownValidator validator;

    public WikiAtomicFilePublisher(AtomicWikiFileCommitter committer,
                                   WikiPublishedMarkdownValidator validator) {
        this.committer = committer;
        this.validator = validator;
    }

    public StagedWikiFile stage(Path target, String content, String expectedHash, WikiDraft draft) {
        rejectExistingTarget(target);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".wiki-publish-", ".tmp");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            byte[] staged = Files.readAllBytes(temporary);
            validator.validate(staged, content, expectedHash, draft);
            return new StagedWikiFile(temporary, target, expectedHash);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw filesystem("Could not stage CREATE Wiki Markdown", exception);
        }
    }

    public void commit(StagedWikiFile staged) {
        try {
            rejectExistingTarget(staged.targetPath());
            committer.commit(staged.temporaryPath(), staged.targetPath());
        } catch (FileAlreadyExistsException exception) {
            throw new WikiPublishException(WikiPublishException.Reason.TARGET_CONFLICT,
                    "CREATE target appeared before the atomic commit and was not overwritten", exception);
        } catch (IOException | UnsupportedOperationException exception) {
            throw filesystem("Filesystem does not support the required atomic no-replace CREATE", exception);
        } finally {
            deleteQuietly(staged.temporaryPath());
        }
    }

    public boolean compensate(Path target, String expectedHash) {
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !WikiContentHash.sha256(Files.readAllBytes(target)).equals(expectedHash)) {
                return false;
            }
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            return false;
        }
    }

    public boolean matches(Path target, String expectedHash) {
        try {
            return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && WikiContentHash.sha256(Files.readAllBytes(target)).equals(expectedHash);
        } catch (IOException exception) {
            return false;
        }
    }

    public void discard(StagedWikiFile staged) {
        if (staged != null) {
            deleteQuietly(staged.temporaryPath());
        }
    }

    private static void rejectExistingTarget(Path target) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WikiPublishException(WikiPublishException.Reason.TARGET_CONFLICT,
                    "CREATE target already exists and will not be overwritten: " + target.getFileName());
        }
    }

    private static WikiPublishException filesystem(String message, Exception cause) {
        return new WikiPublishException(WikiPublishException.Reason.FILESYSTEM_FAILURE, message, cause);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A hidden temp file can be reconciled later; it is never the visible final Markdown target.
        }
    }
}
