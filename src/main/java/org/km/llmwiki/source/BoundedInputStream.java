package org.km.llmwiki.source;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Input stream that cannot consume more than the configured finite byte bound. */
final class BoundedInputStream extends FilterInputStream {

    private final long maximumBytes;
    private long consumedBytes;
    private long markedBytes;

    BoundedInputStream(InputStream input, long maximumBytes) {
        super(input);
        this.maximumBytes = maximumBytes;
    }

    @Override
    public int read() throws IOException {
        if (consumedBytes >= maximumBytes) {
            int next = in.read();
            if (next >= 0) {
                throw new DocumentParserResourceLimitException(
                        DocumentParserResourceLimitException.Resource.INPUT_BYTES);
            }
            return -1;
        }
        int value = in.read();
        if (value >= 0) {
            consumedBytes++;
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (consumedBytes >= maximumBytes) {
            return read();
        }
        int allowedLength = (int) Math.min(length, maximumBytes - consumedBytes);
        int count = in.read(bytes, offset, allowedLength);
        if (count > 0) {
            consumedBytes += count;
        }
        return count;
    }

    @Override
    public long skip(long bytes) throws IOException {
        if (bytes <= 0 || consumedBytes >= maximumBytes) {
            return 0;
        }
        long skipped = in.skip(Math.min(bytes, maximumBytes - consumedBytes));
        consumedBytes += skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), maximumBytes - consumedBytes);
    }

    @Override
    public void mark(int readLimit) {
        in.mark(readLimit);
        markedBytes = consumedBytes;
    }

    @Override
    public void reset() throws IOException {
        in.reset();
        consumedBytes = markedBytes;
    }
}
