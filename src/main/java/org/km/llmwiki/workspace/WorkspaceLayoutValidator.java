package org.km.llmwiki.workspace;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.km.llmwiki.web.DiagnosticRedaction;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceLayoutValidator {

    public static final List<String> DIRECTORY_NAMES = List.of(
            "inbox", "archive", "vault", "data", "config", "logs", "temp");

    /**
     * 在不變更 filesystem 的前提下檢查已註冊 workspace 的 layout。
     *
     * <p>此方法可安全用於 GET endpoint 與 startup。缺少的 rebuildable directory 會回報為
     * invalid，不會因 validation 副作用而建立。
     */
    public LayoutReport validate(Path root) {
        return inspect(root, false);
    }

    /**
     * 明確修復既有 workspace root 下缺少的 rebuildable child directories。root 本身永遠不會
     * 被建立，呼叫端必須從 workspace authority 取得 root，不得使用 request 傳入的 arbitrary path。
     */
    public LayoutReport repair(Path root) {
        return inspect(root, true);
    }

    private LayoutReport inspect(Path root, boolean repairMissingDirectories) {
        List<String> repaired = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        Inspection rootInspection = inspectPath(root);
        if (rootInspection.state() == InspectionState.MISSING) {
            problems.add("root directory does not exist");
            return report(repaired, problems);
        }
        if (rootInspection.state() == InspectionState.NOT_DIRECTORY) {
            problems.add("root path is not a directory");
            return report(repaired, problems);
        }
        if (rootInspection.state() == InspectionState.UNAVAILABLE) {
            problems.add("could not inspect root directory: " + rootInspection.detail());
            return report(repaired, problems);
        }

        for (String directoryName : DIRECTORY_NAMES) {
            Path directory = root.resolve(directoryName);
            Inspection inspection = inspectPath(directory);
            if (inspection.state() == InspectionState.MISSING && repairMissingDirectories) {
                try {
                    // createDirectory 會刻意拒絕重建在 root inspection 與 explicit repair 之間
                    // 消失的 root。
                    Files.createDirectory(directory);
                    repaired.add(directoryName);
                    continue;
                } catch (FileAlreadyExistsException exception) {
                    Inspection current = inspectPath(directory);
                    if (current.state() == InspectionState.DIRECTORY) {
                        continue;
                    }
                    inspection = current;
                } catch (IOException | SecurityException exception) {
                    problems.add("could not create directory '" + directoryName + "': "
                            + safeDetail(exception, "filesystem rejected the operation"));
                    continue;
                }
            }
            if (inspection.state() == InspectionState.MISSING) {
                problems.add("'" + directoryName + "' directory does not exist");
            } else if (inspection.state() == InspectionState.NOT_DIRECTORY) {
                problems.add("'" + directoryName + "' exists but is not a directory");
            } else if (inspection.state() == InspectionState.UNAVAILABLE) {
                problems.add("could not inspect directory '" + directoryName + "': "
                        + inspection.detail());
            }
        }

        return report(repaired, problems);
    }

    private static Inspection inspectPath(Path path) {
        if (path == null) {
            return new Inspection(InspectionState.UNAVAILABLE, "workspace path is unavailable");
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new Inspection(attributes.isDirectory()
                    ? InspectionState.DIRECTORY : InspectionState.NOT_DIRECTORY, "");
        } catch (NoSuchFileException exception) {
            return new Inspection(InspectionState.MISSING, "");
        } catch (IOException | SecurityException exception) {
            return new Inspection(InspectionState.UNAVAILABLE,
                    safeDetail(exception, "filesystem state unavailable"));
        }
    }

    private static LayoutReport report(List<String> repaired, List<String> problems) {
        return new LayoutReport(problems.isEmpty(), List.copyOf(repaired), List.copyOf(problems));
    }

    private static String safeDetail(Exception exception, String fallback) {
        return DiagnosticRedaction.publicMessage(exception.getMessage(), fallback);
    }

    private enum InspectionState {
        DIRECTORY,
        MISSING,
        NOT_DIRECTORY,
        UNAVAILABLE
    }

    private record Inspection(InspectionState state, String detail) {
    }

    public record LayoutReport(boolean valid, List<String> repairedDirectories, List<String> problems) {
    }
}
