package org.km.llmwiki.source;

public class SourceChunkNotFoundException extends RuntimeException {

    public SourceChunkNotFoundException(long chunkId) {
        super("找不到 Source Chunk：" + chunkId);
    }
}
