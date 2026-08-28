package org.km.llmwiki.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.LlmProposalAction;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Deterministically converts one approved persistence projection into the Wiki Draft contract. */
@Component
public class WikiDraftConverter {

    private static final List<String> FRONTMATTER_FIELDS = List.of(
            "title", "pageType", "summary", "tags", "aliases", "sourceDocumentIds", "sourceChunkIds");

    private final ObjectMapper objectMapper;
    private final CandidatePageTypeResolver pageTypeResolver;
    private final WikiLogicalPathAuthority pathAuthority;

    public WikiDraftConverter(ObjectMapper objectMapper, CandidatePageTypeResolver pageTypeResolver,
                              WikiLogicalPathAuthority pathAuthority) {
        this.objectMapper = objectMapper;
        this.pageTypeResolver = pageTypeResolver;
        this.pathAuthority = pathAuthority;
    }

    public WikiDraft convert(WikiDraftConversionSource source) {
        if (source.status() != KnowledgeProposalStatus.APPROVED) {
            throw new WikiDraftValidationException(WikiDraftValidationException.Reason.PROPOSAL_NOT_APPROVED,
                    "Only APPROVED knowledge proposals can produce Wiki Drafts");
        }
        if (source.action() != LlmProposalAction.CREATE && source.action() != LlmProposalAction.MERGE) {
            throw new WikiDraftValidationException(WikiDraftValidationException.Reason.UNSUPPORTED_ACTION,
                    "Proposal action " + source.action() + " does not produce renderable Wiki content");
        }

        JsonNode data = normalizedData(source.normalizedDataJson());
        WikiPageType requestedPageType = optionalPageType(data);
        WikiPageType pageType = pageTypeResolver.resolve(source.candidateType(), requestedPageType);
        String title = optionalInline(data, "title", source.candidateTitle());
        String summary = optionalInline(data, "summary", source.candidateSummary());
        List<String> tags = normalizedStrings(data, "tags", true);
        List<String> aliases = normalizedStrings(data, "aliases", false);
        List<Long> sourceChunkIds = validatedSourceChunkIds(data, source);
        List<WikiDraftEvidence> evidence = normalizedEvidence(source.proposalEvidence());
        List<WikiDraftSection> sections = sections(data, summary);
        List<WikiDraftWikilink> wikilinks = wikilinks(data);

        WikiPage page = WikiPage.create(title, pageType, summary, tags, aliases, List.of(source.documentId()));
        String authoritativeLogicalPath = pathAuthority.resolveLogicalPath(pageType, title);
        if (!page.logicalRelativePath().equals(authoritativeLogicalPath)) {
            throw new WikiDraftValidationException(WikiDraftValidationException.Reason.PATH_CONTRACT_MISMATCH,
                    "WikiPage.create and active workspace path authority produced different logical paths");
        }

        WikiDraftTarget target = target(source, authoritativeLogicalPath);
        WikiDraftFrontmatter frontmatter = new WikiDraftFrontmatter(page.title(), page.pageType(), summary,
                page.tags(), page.aliases(), page.sourceDocumentIds(), sourceChunkIds);
        WikiDraftContentContract contentContract = new WikiDraftContentContract("wiki-draft/v1",
                FRONTMATTER_FIELDS, sections.stream().map(WikiDraftSection::heading).toList(), true, true);
        return new WikiDraft(source.proposalId(), source.action(), page.pageType(), page.title(), target, frontmatter,
                sections, wikilinks, evidence, sourceChunkIds, contentContract);
    }

    private JsonNode normalizedData(String json) {
        try {
            JsonNode data = objectMapper.readTree(json);
            if (data == null || !data.isObject()) {
                throw invalid("normalizedDataJson must be a JSON object");
            }
            return data;
        } catch (JsonProcessingException exception) {
            throw new WikiDraftValidationException(WikiDraftValidationException.Reason.INVALID_NORMALIZED_DATA,
                    "normalizedDataJson must be valid JSON", exception);
        }
    }

    private static WikiPageType optionalPageType(JsonNode data) {
        JsonNode value = data.get("pageType");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid("pageType must be a controlled non-blank string");
        }
        try {
            return WikiPageType.from(value.asText());
        } catch (WikiPathValidationException exception) {
            throw new WikiDraftValidationException(
                    WikiDraftValidationException.Reason.UNSUPPORTED_CANDIDATE_MAPPING,
                    "Requested pageType is not supported: " + value.asText(), exception);
        }
    }

    private static String optionalInline(JsonNode data, String field, String fallback) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            return normalizeInline(fallback, field);
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return normalizeInline(value.asText(), field);
    }

    private static List<String> normalizedStrings(JsonNode data, String field, boolean lowerCase) {
        JsonNode values = data.get(field);
        if (values == null || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw invalid(field + " must be an array of strings");
        }
        Set<String> normalized = new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid(field + " must contain only strings");
            }
            String item = normalizeInline(value.asText(), field);
            normalized.add(lowerCase ? item.toLowerCase(Locale.ROOT) : item);
        }
        return List.copyOf(normalized);
    }

    private static List<Long> validatedSourceChunkIds(JsonNode data, WikiDraftConversionSource source) {
        List<Long> evidenceIds = source.proposalEvidence().stream()
                .map(KnowledgeProposalEvidence::sourceChunkId).sorted().toList();
        if (evidenceIds.isEmpty() || new HashSet<>(evidenceIds).size() != evidenceIds.size()
                || evidenceIds.stream().anyMatch(id -> id <= 0)) {
            throw invalidEvidence("Proposal evidence must contain unique positive Source Chunk ids");
        }
        if (!evidenceIds.containsAll(source.candidateEvidenceSourceChunkIds())) {
            throw invalidEvidence("Proposal evidence must retain every Source Chunk cited by its candidate");
        }

        JsonNode values = data.get("sourceChunkIds");
        if (values == null || values.isNull()) {
            return evidenceIds;
        }
        if (!values.isArray() || values.isEmpty()) {
            throw invalidEvidence("sourceChunkIds must be a non-empty array");
        }
        List<Long> declared = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
                throw invalidEvidence("sourceChunkIds must contain only positive integer ids");
            }
            declared.add(value.asLong());
        }
        if (new HashSet<>(declared).size() != declared.size()
                || !new HashSet<>(declared).equals(new HashSet<>(evidenceIds))) {
            throw invalidEvidence("sourceChunkIds must exactly match persisted proposal evidence");
        }
        return evidenceIds;
    }

    private static List<WikiDraftEvidence> normalizedEvidence(List<KnowledgeProposalEvidence> values) {
        return values.stream()
                .sorted(Comparator.comparingLong(KnowledgeProposalEvidence::sourceChunkId))
                .map(value -> new WikiDraftEvidence(value.sourceChunkId(), value.chunkNo(), value.pageNo(),
                        optionalNormalized(value.section()), optionalNormalized(value.headingPath()),
                        normalizeContent(value.content(), "evidence content")))
                .toList();
    }

    private static List<WikiDraftSection> sections(JsonNode data, String summary) {
        JsonNode values = data.get("sections");
        if (values == null || values.isNull()) {
            return List.of(new WikiDraftSection("Summary", summary));
        }
        if (!values.isArray() || values.isEmpty()) {
            throw invalid("sections must be a non-empty array");
        }
        List<WikiDraftSection> sections = new ArrayList<>();
        Set<String> headings = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isObject()) {
                throw invalid("sections must contain objects");
            }
            String heading = requiredInline(value, "heading");
            String content = requiredContent(value, "content");
            if (!headings.add(heading.toLowerCase(Locale.ROOT))) {
                throw invalid("section headings must be unique");
            }
            sections.add(new WikiDraftSection(heading, content));
        }
        return List.copyOf(sections);
    }

    private static List<WikiDraftWikilink> wikilinks(JsonNode data) {
        JsonNode values = data.get("wikilinks");
        if (values == null || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw invalid("wikilinks must be an array");
        }
        List<WikiDraftWikilink> links = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) {
                throw invalid("wikilinks must contain objects");
            }
            String targetTitle = requiredInline(value, "targetTitle");
            String label = optionalInline(value, "label", targetTitle);
            links.add(new WikiDraftWikilink(targetTitle, label));
        }
        return links.stream().distinct()
                .sorted(Comparator.comparing(WikiDraftWikilink::targetTitle)
                        .thenComparing(WikiDraftWikilink::label))
                .toList();
    }

    private static WikiDraftTarget target(WikiDraftConversionSource source, String logicalPath) {
        if (source.action() == LlmProposalAction.CREATE) {
            if (source.mergeTargetReference() != null) {
                throw invalid("CREATE proposal must not contain a merge target reference");
            }
            return WikiDraftTarget.createNew(logicalPath);
        }
        if (source.mergeTargetReference() == null || source.mergeTargetReference().isBlank()) {
            throw invalid("MERGE proposal requires an unresolved target reference for STORY-403");
        }
        return WikiDraftTarget.existingReference(source.mergeTargetReference());
    }

    private static String requiredInline(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return normalizeInline(value.asText(), field);
    }

    private static String requiredContent(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return normalizeContent(value.asText(), field);
    }

    private static String normalizeInline(String value, String field) {
        if (value == null) {
            throw invalid(field + " must not be null");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeContent(String value, String field) {
        if (value == null) {
            throw invalid(field + " must not be null");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.lines().map(line -> line.replaceFirst("\\s+$", ""))
                .collect(java.util.stream.Collectors.joining("\n")).strip();
        if (normalized.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return normalized;
    }

    private static String optionalNormalized(String value) {
        return value == null || value.isBlank() ? null : normalizeInline(value, "evidence metadata");
    }

    private static WikiDraftValidationException invalid(String message) {
        return new WikiDraftValidationException(WikiDraftValidationException.Reason.INVALID_NORMALIZED_DATA, message);
    }

    private static WikiDraftValidationException invalidEvidence(String message) {
        return new WikiDraftValidationException(WikiDraftValidationException.Reason.INVALID_EVIDENCE, message);
    }
}
