package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic line-oriented unified diff suitable for human review. */
@Component
public class WikiDraftDiffRenderer {

    private static final long MAX_LCS_CELLS = 4_000_000L;

    public String render(String targetPath, String before, String after) {
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        List<DiffLine> changes = ((long) oldLines.size() * newLines.size() <= MAX_LCS_CELLS)
                ? lcs(oldLines, newLines)
                : coarse(oldLines, newLines);
        StringBuilder diff = new StringBuilder()
                .append("--- a/").append(targetPath).append('\n')
                .append("+++ b/").append(targetPath).append('\n')
                .append("@@ -1,").append(oldLines.size())
                .append(" +1,").append(newLines.size()).append(" @@\n");
        changes.forEach(line -> diff.append(line.prefix()).append(line.value()).append('\n'));
        return diff.toString();
    }

    private static List<DiffLine> lcs(List<String> oldLines, List<String> newLines) {
        int[][] lengths = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int oldIndex = oldLines.size() - 1; oldIndex >= 0; oldIndex--) {
            for (int newIndex = newLines.size() - 1; newIndex >= 0; newIndex--) {
                lengths[oldIndex][newIndex] = oldLines.get(oldIndex).equals(newLines.get(newIndex))
                        ? lengths[oldIndex + 1][newIndex + 1] + 1
                        : Math.max(lengths[oldIndex + 1][newIndex], lengths[oldIndex][newIndex + 1]);
            }
        }
        List<DiffLine> result = new ArrayList<>();
        int oldIndex = 0;
        int newIndex = 0;
        while (oldIndex < oldLines.size() && newIndex < newLines.size()) {
            if (oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                result.add(new DiffLine(' ', oldLines.get(oldIndex++)));
                newIndex++;
            } else if (lengths[oldIndex + 1][newIndex] >= lengths[oldIndex][newIndex + 1]) {
                result.add(new DiffLine('-', oldLines.get(oldIndex++)));
            } else {
                result.add(new DiffLine('+', newLines.get(newIndex++)));
            }
        }
        while (oldIndex < oldLines.size()) {
            result.add(new DiffLine('-', oldLines.get(oldIndex++)));
        }
        while (newIndex < newLines.size()) {
            result.add(new DiffLine('+', newLines.get(newIndex++)));
        }
        return result;
    }

    private static List<DiffLine> coarse(List<String> oldLines, List<String> newLines) {
        List<DiffLine> result = new ArrayList<>(oldLines.size() + newLines.size());
        oldLines.forEach(line -> result.add(new DiffLine('-', line)));
        newLines.forEach(line -> result.add(new DiffLine('+', line)));
        return result;
    }

    private static List<String> lines(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(Arrays.asList(value.split("\\n", -1)));
        if (!values.isEmpty() && values.getLast().isEmpty()) {
            values.removeLast();
        }
        return values;
    }

    private record DiffLine(char prefix, String value) {
    }
}
