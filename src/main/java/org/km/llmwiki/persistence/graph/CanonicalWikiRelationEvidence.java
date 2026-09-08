package org.km.llmwiki.persistence.graph;

import org.km.llmwiki.wiki.WikiTargetReference;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** 從已驗證 hash 的受控 published Markdown 擷取 profile v2 結構化 relation evidence。 */
record CanonicalWikiRelationEvidence(Set<String> tags, Set<Long> sourceDocumentIds,
                                     Set<String> normalizedLinkTargets) {

    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^]\\r\\n]+)]]");
    private static final int MAX_EVIDENCE_PER_KIND = 2_000;

    CanonicalWikiRelationEvidence {
        tags = Set.copyOf(tags);
        sourceDocumentIds = Set.copyOf(sourceDocumentIds);
        normalizedLinkTargets = Set.copyOf(normalizedLinkTargets);
    }

    static CanonicalWikiRelationEvidence parse(String markdown) {
        if (markdown == null || !markdown.startsWith("---\n")) throw invalid();
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) throw invalid();
        String frontmatter = markdown.substring(4, end);
        var tags = new TreeSet<String>();
        var sources = new TreeSet<Long>();
        readList(frontmatter, "tags", value -> tags.add(normalizeTag(value)));
        readList(frontmatter, "sources", value -> sources.add(parseSource(value)));

        var links = new TreeSet<String>();
        String body = markdown.substring(end + "\n---\n".length());
        var matcher = WIKILINK.matcher(body);
        int linkCount = 0;
        while (matcher.find()) {
            requireBounded(++linkCount);
            String target = matcher.group(1);
            int alias = target.indexOf('|');
            if (alias >= 0) target = target.substring(0, alias);
            if (target.isBlank() || target.codePointCount(0, target.length()) > 200
                    || target.contains("#") || target.contains("/") || target.contains("\\")) {
                throw invalid();
            }
            links.add(WikiTargetReference.normalizeTitle(target));
        }
        return new CanonicalWikiRelationEvidence(tags, sources, links);
    }

    private static void readList(String frontmatter, String field,
                                 java.util.function.Consumer<String> consumer) {
        String[] lines = frontmatter.split("\n", -1);
        List<String> values = new ArrayList<>();
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith(field + ":")) continue;
            if (found || !(line.equals(field + ":") || line.equals(field + ": []"))) throw invalid();
            found = true;
            if (line.endsWith("[]")) continue;
            while (i + 1 < lines.length && lines[i + 1].startsWith("  - ")) {
                values.add(decodeQuoted(lines[++i].substring(4)));
                requireBounded(values.size());
            }
            if (i + 1 < lines.length && !lines[i + 1].isEmpty()
                    && Character.isWhitespace(lines[i + 1].charAt(0))) {
                throw invalid();
            }
        }
        values.forEach(consumer);
    }

    private static String decodeQuoted(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            throw invalid();
        }
        StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char current = value.charAt(i);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++i >= value.length() - 1) throw invalid();
            char escaped = value.charAt(i);
            if (escaped == '\\' || escaped == '"') decoded.append(escaped);
            else if (escaped == 'n') decoded.append('\n');
            else throw invalid();
        }
        return decoded.toString();
    }

    private static String normalizeTag(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.lines().count() != 1
                || normalized.codePointCount(0, normalized.length()) > 200) throw invalid();
        return normalized;
    }

    private static long parseSource(String value) {
        if (!value.matches("document:[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(value.substring("document:".length()));
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static void requireBounded(int size) {
        if (size > MAX_EVIDENCE_PER_KIND) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Canonical Wiki relation evidence is invalid");
    }
}
