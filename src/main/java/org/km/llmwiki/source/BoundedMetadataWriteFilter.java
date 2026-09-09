package org.km.llmwiki.source;

import org.apache.tika.metadata.writefilter.MetadataWriteFilter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prevents Tika from accumulating an unbounded metadata map before the application sees it.
 */
final class BoundedMetadataWriteFilter implements MetadataWriteFilter {

    private final int maximumCharacters;

    BoundedMetadataWriteFilter(int maximumCharacters) {
        this.maximumCharacters = maximumCharacters;
    }

    @Override
    public void filterExisting(Map<String, String[]> metadata) {
        ensureWithinLimit(metadata);
    }

    @Override
    public void add(String name, String value, Map<String, String[]> metadata) {
        Map<String, String[]> projected = copy(metadata);
        String[] values = projected.get(name);
        if (values == null) {
            projected.put(name, new String[]{value});
        } else {
            String[] appended = Arrays.copyOf(values, values.length + 1);
            appended[values.length] = value;
            projected.put(name, appended);
        }
        ensureWithinLimit(projected);
        metadata.put(name, projected.get(name));
    }

    @Override
    public void set(String name, String value, Map<String, String[]> metadata) {
        Map<String, String[]> projected = copy(metadata);
        projected.put(name, new String[]{value});
        ensureWithinLimit(projected);
        metadata.put(name, projected.get(name));
    }

    private void ensureWithinLimit(Map<String, String[]> metadata) {
        long characters = 0;
        for (Map.Entry<String, String[]> entry : metadata.entrySet()) {
            characters += entry.getKey().length();
            String[] values = entry.getValue();
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    characters += 2;
                }
                characters += values[index].length();
                if (characters > maximumCharacters) {
                    throw new MetadataLimitExceededException();
                }
            }
        }
    }

    private static Map<String, String[]> copy(Map<String, String[]> metadata) {
        Map<String, String[]> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> copy.put(key, value.clone()));
        return copy;
    }
}
