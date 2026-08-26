package org.km.llmwiki.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class WorkspaceLayoutValidator {

    public static final List<String> DIRECTORY_NAMES = List.of(
            "inbox", "archive", "vault", "data", "config", "logs", "temp");

    public LayoutReport validateAndRepair(Path root) {
        List<String> repaired = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        if (!Files.exists(root)) {
            problems.add("root directory does not exist: " + root);
            return new LayoutReport(false, repaired, problems);
        }
        if (!Files.isDirectory(root)) {
            problems.add("root path is not a directory: " + root);
            return new LayoutReport(false, repaired, problems);
        }

        for (String directoryName : DIRECTORY_NAMES) {
            Path directory = root.resolve(directoryName);
            if (!Files.exists(directory)) {
                try {
                    Files.createDirectories(directory);
                    repaired.add(directoryName);
                } catch (Exception exception) {
                    problems.add("could not create directory '" + directoryName + "': " + exception.getMessage());
                }
            } else if (!Files.isDirectory(directory)) {
                problems.add("'" + directoryName + "' exists but is not a directory");
            }
        }

        return new LayoutReport(problems.isEmpty(), List.copyOf(repaired), List.copyOf(problems));
    }

    public record LayoutReport(boolean valid, List<String> repairedDirectories, List<String> problems) {
    }
}
