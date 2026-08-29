package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Same-directory staging, validation, optimistic recheck, and atomic replacement for MERGE. */
@Component
public class WikiAtomicFileReplacer {

    private final AtomicWikiFileReplacer replacer;
    private final WikiPublishedMarkdownValidator validator;
    private final ConcurrentHashMap<Path, ReentrantLock> targetLocks = new ConcurrentHashMap<>();

    public WikiAtomicFileReplacer(AtomicWikiFileReplacer replacer,
                                  WikiPublishedMarkdownValidator validator) {
        this.replacer = replacer;
        this.validator = validator;
    }

    public StagedWikiReplacement stage(Path target, String content, String expectedBeforeHash,
                                       String expectedAfterHash, WikiDraft draft, String publishedTitle) {
        byte[] beforeBytes = readExpectedTarget(target, expectedBeforeHash);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".wiki-merge-", ".tmp");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            writeForced(temporary, bytes);
            byte[] staged = Files.readAllBytes(temporary);
            validator.validate(staged, content, expectedAfterHash, draft, publishedTitle);
            return new StagedWikiReplacement(temporary, target, beforeBytes,
                    expectedBeforeHash, expectedAfterHash);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw filesystem("Could not stage MERGE Wiki Markdown", exception);
        }
    }

    public void commit(StagedWikiReplacement staged) {
        ReentrantLock lock = lock(staged.targetPath());
        lock.lock();
        try {
            readExpectedTarget(staged.targetPath(), staged.beforeHash());
            try {
                replacer.replace(staged.temporaryPath(), staged.targetPath());
            } catch (IOException | UnsupportedOperationException exception) {
                if (!matches(staged.targetPath(), staged.afterHash())) {
                    throw filesystem("Filesystem does not support the required atomic MERGE replacement", exception);
                }
            } catch (RuntimeException exception) {
                throw filesystem("Atomic MERGE replacement failed unexpectedly", exception);
            }
            if (!matches(staged.targetPath(), staged.afterHash())) {
                throw filesystem("Atomic MERGE replacement did not produce the validated content", null);
            }
        } finally {
            deleteQuietly(staged.temporaryPath());
            unlock(staged.targetPath(), lock);
        }
    }

    public boolean compensate(StagedWikiReplacement staged) {
        ReentrantLock lock = lock(staged.targetPath());
        lock.lock();
        Path restore = null;
        try {
            if (matches(staged.targetPath(), staged.beforeHash())) {
                return true;
            }
            if (!matches(staged.targetPath(), staged.afterHash())) {
                return false;
            }
            restore = Files.createTempFile(staged.targetPath().getParent(), ".wiki-merge-restore-", ".tmp");
            writeForced(restore, staged.beforeBytes());
            if (!WikiContentHash.sha256(Files.readAllBytes(restore)).equals(staged.beforeHash())) {
                return false;
            }
            replacer.replace(restore, staged.targetPath());
            restore = null;
            return matches(staged.targetPath(), staged.beforeHash());
        } catch (IOException | RuntimeException exception) {
            // Atomic move implementations may throw after the rename became visible.
            return matches(staged.targetPath(), staged.beforeHash());
        } finally {
            deleteQuietly(restore);
            unlock(staged.targetPath(), lock);
        }
    }

    public boolean matches(Path target, String expectedHash) {
        try {
            return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)
                    && WikiContentHash.sha256(Files.readAllBytes(target)).equals(expectedHash);
        } catch (IOException exception) {
            return false;
        }
    }

    public void discard(StagedWikiReplacement staged) {
        if (staged != null) {
            deleteQuietly(staged.temporaryPath());
        }
    }

    private static byte[] readExpectedTarget(Path target, String expectedHash) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WikiPublishException(WikiPublishException.Reason.TARGET_MISSING,
                    "MERGE target no longer exists and will not be recreated");
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WikiPublishException(WikiPublishException.Reason.TARGET_CONFLICT,
                    "MERGE target must remain a regular non-symlink file");
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String currentHash = WikiContentHash.sha256(bytes);
            if (!currentHash.equals(expectedHash)) {
                throw new WikiPublishException(WikiPublishException.Reason.OPTIMISTIC_LOCK_CONFLICT,
                        "MERGE target was modified after Draft creation");
            }
            return bytes;
        } catch (IOException exception) {
            throw filesystem("MERGE target content could not be read", exception);
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private ReentrantLock lock(Path target) {
        return targetLocks.computeIfAbsent(target.toAbsolutePath().normalize(), ignored -> new ReentrantLock());
    }

    private void unlock(Path target, ReentrantLock lock) {
        lock.unlock();
        if (!lock.hasQueuedThreads()) {
            targetLocks.remove(target.toAbsolutePath().normalize(), lock);
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
            // A hidden temp file can be reconciled later; it is never a visible Wiki target.
        }
    }
}
