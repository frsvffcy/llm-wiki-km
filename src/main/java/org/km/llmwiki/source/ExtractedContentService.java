package org.km.llmwiki.source;

import org.km.llmwiki.web.PageResponse;
import org.km.llmwiki.search.SourceChunkIndexingService;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class ExtractedContentService {

    static final int PREVIEW_CHUNK_SIZE = 2_000;
    private static final int MAX_PAGE_SIZE = 200;

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;
    private final DocumentParserRegistry parserRegistry;
    private final ExtractedContentRepository extractedContentRepository;
    private final ExtractedContentNormalizer extractedContentNormalizer;
    private final ScannedPdfDetector scannedPdfDetector;
    private final SourceChunker sourceChunker;
    private final SourceChunkRepository sourceChunkRepository;
    private final SourceChunkIndexingService sourceChunkIndexingService;

    public ExtractedContentService(WorkspaceService workspaceService, DocumentRepository documentRepository,
                                   DocumentParserRegistry parserRegistry,
                                   ExtractedContentRepository extractedContentRepository,
                                   ExtractedContentNormalizer extractedContentNormalizer,
                                   ScannedPdfDetector scannedPdfDetector,
                                   SourceChunker sourceChunker,
                                   SourceChunkRepository sourceChunkRepository,
                                   SourceChunkIndexingService sourceChunkIndexingService) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.parserRegistry = parserRegistry;
        this.extractedContentRepository = extractedContentRepository;
        this.extractedContentNormalizer = extractedContentNormalizer;
        this.scannedPdfDetector = scannedPdfDetector;
        this.sourceChunker = sourceChunker;
        this.sourceChunkRepository = sourceChunkRepository;
        this.sourceChunkIndexingService = sourceChunkIndexingService;
    }

    @Transactional(noRollbackFor = DocumentExtractionException.class)
    public ExtractionResponse extract(long documentId) {
        WorkspaceResponse workspace = activeWorkspace();
        DocumentExtractionTarget document = target(workspace, documentId);
        scheduleSourceIndexSync(workspace.id(), document.documentId());
        DocumentParser parser = parserRegistry.findParser(document.mimeType(), document.fileName())
                .orElse(null);
        if (parser == null) {
            return unsupported(document);
        }

        try {
            Path source = resolveSource(workspace, document.sourcePath());
            ParsedDocument parsed = parser.parse(source);
            String parserError = parsed.metadata().get("parseError");
            if (parserError != null && !parserError.isBlank()) {
                throw extractionFailure(document, DocumentStatus.FAILED,
                        "EXTRACTION_PARSE_FAILED", "文件文字抽取失敗");
            }
            ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization =
                    extractedContentNormalizer.canonicalize(parsed.content());
            String normalizedContent = canonicalNormalization.content();
            if (scannedPdfDetector.requiresOcr(source, document.mimeType(), document.fileName(), normalizedContent)) {
                extractedContentRepository.deleteByDocumentId(document.documentId());
                sourceChunkRepository.deleteByDocumentId(document.documentId());
                documentRepository.markExtractionFailed(document.documentId(), DocumentStatus.NEED_OCR,
                        "OCR_REQUIRED", "PDF 缺乏可用文字層，需先進行 OCR");
                return new ExtractionResponse(document.documentId(), DocumentStatus.NEED_OCR.name(), 0,
                        "OCR_REQUIRED", "PDF 缺乏可用文字層，需先進行 OCR");
            }
            int chunkCount = chunkCount(normalizedContent);
            extractedContentRepository.save(document.documentId(), normalizedContent, chunkCount);
            sourceChunkRepository.replaceForDocument(document.documentId(),
                    sourceChunker.chunk(parsed.content(), canonicalNormalization));
            documentRepository.markExtractionSucceeded(document.documentId(), sha256(normalizedContent));
            return new ExtractionResponse(document.documentId(), DocumentStatus.PROCESSED.name(), chunkCount,
                    null, null);
        } catch (IOException exception) {
            throw extractionFailure(document, DocumentStatus.FAILED,
                    "EXTRACTION_SOURCE_UNAVAILABLE", "無法讀取待抽取的文件");
        }
    }

    public ExtractedContentPreviewResponse preview(long documentId, Integer page, Integer size) {
        WorkspaceResponse workspace = activeWorkspace();
        DocumentExtractionTarget document = target(workspace, documentId);
        ExtractedContentRecord extracted = extractedContentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new DocumentExtractionException(
                        "EXTRACTED_CONTENT_NOT_FOUND", "尚無可預覽的抽取文字"));
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        validatePage(pageNumber, pageSize);
        List<ExtractedContentChunk> chunks = chunksFor(extracted.content(), pageNumber, pageSize);
        PageResponse.PageMeta pageMeta = PageResponse.of(chunks, pageNumber, pageSize,
                extracted.chunkCount()).page();
        return new ExtractedContentPreviewResponse(documentId, document.parseStatus(), extracted.chunkCount(),
                chunks, pageMeta);
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new);
    }

    private DocumentExtractionTarget target(WorkspaceResponse workspace, long documentId) {
        return documentRepository.findExtractionTarget(workspace.id(), documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private DocumentExtractionException extractionFailure(DocumentExtractionTarget document,
                                                          DocumentStatus parseStatus,
                                                          String errorCode, String message) {
        extractedContentRepository.deleteByDocumentId(document.documentId());
        sourceChunkRepository.deleteByDocumentId(document.documentId());
        documentRepository.markExtractionFailed(document.documentId(), parseStatus, errorCode, message);
        return new DocumentExtractionException(errorCode, message);
    }

    private ExtractionResponse unsupported(DocumentExtractionTarget document) {
        String errorCode = "EXTRACTION_UNSUPPORTED_TYPE";
        String errorMessage = "不支援此文件類型的文字抽取";
        extractedContentRepository.deleteByDocumentId(document.documentId());
        sourceChunkRepository.deleteByDocumentId(document.documentId());
        documentRepository.markExtractionFailed(document.documentId(), DocumentStatus.UNSUPPORTED,
                errorCode, errorMessage);
        return new ExtractionResponse(document.documentId(), DocumentStatus.UNSUPPORTED.name(), 0,
                errorCode, errorMessage);
    }

    /** FTS is refreshed only after canonical extraction state has committed successfully. */
    private void scheduleSourceIndexSync(long workspaceId, long documentId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            synchronizeWithoutAffectingCanonicalResult(workspaceId, documentId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                synchronizeWithoutAffectingCanonicalResult(workspaceId, documentId);
            }
        });
    }

    private void synchronizeWithoutAffectingCanonicalResult(long workspaceId, long documentId) {
        try {
            sourceChunkIndexingService.synchronizeDocument(workspaceId, documentId);
        } catch (RuntimeException ignored) {
            // Canonical Source Chunk commit must never be rewritten as an extraction failure.
        }
    }

    private static Path resolveSource(WorkspaceResponse workspace, String sourcePath) throws IOException {
        Path root = Path.of(workspace.rootPath()).toRealPath();
        Path source = root.resolve(sourcePath).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            throw new IOException("document source is unavailable");
        }
        Path realSource = source.toRealPath();
        if (!realSource.startsWith(root)) {
            throw new IOException("document source escapes the workspace");
        }
        return realSource;
    }

    private static int chunkCount(String content) {
        return (content.length() + PREVIEW_CHUNK_SIZE - 1) / PREVIEW_CHUNK_SIZE;
    }

    private static List<ExtractedContentChunk> chunksFor(String content, int page, int size) {
        int firstChunk = Math.multiplyExact(page, size);
        int totalChunks = chunkCount(content);
        int lastChunk = Math.min(firstChunk + size, totalChunks);
        List<ExtractedContentChunk> chunks = new ArrayList<>();
        for (int chunkIndex = firstChunk; chunkIndex < lastChunk; chunkIndex++) {
            int start = chunkIndex * PREVIEW_CHUNK_SIZE;
            int end = Math.min(start + PREVIEW_CHUNK_SIZE, content.length());
            chunks.add(new ExtractedContentChunk(chunkIndex, content.substring(start, end)));
        }
        return List.copyOf(chunks);
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
