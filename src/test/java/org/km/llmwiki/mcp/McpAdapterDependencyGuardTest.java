package org.km.llmwiki.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable architecture guard for the MCP adapter layering (#331): production code in the
 * {@code mcp} package must never reference a {@code @RestController} class, and no
 * {@code @RestController} outside the {@code mcp} package may reference the {@code mcp}
 * package. Both adapters (REST and MCP) delegate to shared application boundaries instead of
 * calling each other.
 *
 * <p>The guard is annotation-based, not name-based: renaming a controller cannot bypass it.
 * It scans compiled production classes and fails loudly on any scan error instead of passing
 * silently.
 */
@Tag("unit")
class McpAdapterDependencyGuardTest {

    private static final String BASE_PACKAGE = "org.km.llmwiki.";
    private static final String MCP_PACKAGE = "org.km.llmwiki.mcp.";
    private static final String MCP_PATH_PREFIX = "org/km/llmwiki/mcp/";

    @Test
    void mcpPackageNeverReferencesRestControllers() {
        Set<String> controllers = restControllers();
        assertThat(controllers)
                .as("the scan must actually observe @RestController classes")
                .contains("org.km.llmwiki.ai.ask.AskController",
                        "org.km.llmwiki.web.RetrievalInspectorController");

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : classReferences(mcpClasses()).entrySet()) {
            String self = displayName(entry.getKey());
            for (String referenced : entry.getValue()) {
                String binary = referenced.replace('/', '.');
                if (!binary.equals(self) && controllers.contains(binary)) {
                    violations.add(self + " -> " + binary);
                }
            }
        }
        assertThat(violations)
                .as("mcp production code must delegate to shared application boundaries, "
                        + "never to @RestController classes")
                .isEmpty();
    }

    @Test
    void restControllersNeverReferenceTheMcpPackage() {
        Set<String> controllers = restControllers();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : classReferences(controllerClasses(controllers))
                .entrySet()) {
            for (String referenced : entry.getValue()) {
                if (referenced.startsWith(MCP_PATH_PREFIX)) {
                    violations.add(displayName(entry.getKey()) + " -> "
                            + referenced.replace('/', '.'));
                }
            }
        }
        assertThat(violations)
                .as("REST transport adapters must not depend on the mcp package")
                .isEmpty();
    }

    private static List<String> mcpClasses() {
        return classFiles().stream()
                .filter(path -> path.startsWith(MCP_PATH_PREFIX))
                .filter(path -> !path.contains("$"))
                .toList();
    }

    private static String displayName(String classPath) {
        String name = classPath.substring(0, classPath.length() - ".class".length());
        return name.replace('/', '.');
    }

    private static List<String> controllerClasses(Set<String> controllers) {
        return controllers.stream()
                .filter(name -> !name.startsWith(MCP_PACKAGE))
                .filter(name -> !name.contains("$"))
                .map(name -> name.replace('.', '/') + ".class")
                .toList();
    }

    private static Set<String> restControllers() {
        Set<String> controllers = new HashSet<>();
        ClassLoader loader = McpAdapterDependencyGuardTest.class.getClassLoader();
        for (String path : classFiles()) {
            if (!path.startsWith("org/km/llmwiki/") || path.contains("$")) {
                continue;
            }
            String binary = path.substring(0, path.length() - ".class".length())
                    .replace('/', '.');
            try {
                Class<?> type = Class.forName(binary, false, loader);
                if (type.getDeclaredAnnotation(RestController.class) != null) {
                    controllers.add(binary);
                }
            } catch (NoClassDefFoundError | ExceptionInInitializerError error) {
                throw new IllegalStateException(
                        "adapter guard could not load production class " + binary, error);
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException(
                        "adapter guard could not find production class " + binary, error);
            }
        }
        return controllers;
    }

    private static List<String> classFiles() {
        Path root = classesRoot();
        try (Stream<Path> files = Files.walk(root.resolve("org/km/llmwiki"))) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(root::relativize)
                    .map(path -> path.toString().replace(java.io.File.separatorChar, '/'))
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("adapter guard could not scan compiled classes", error);
        }
    }

    private static Path classesRoot() {
        try {
            return Path.of(McpToolExecutor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException error) {
            throw new IllegalStateException("adapter guard could not locate compiled classes", error);
        }
    }

    private static Map<String, Set<String>> classReferences(List<String> classPaths) {
        Path root = classesRoot();
        Map<String, Set<String>> references = new HashMap<>();
        for (String path : classPaths) {
            try {
                references.put(path, referencedClasses(root.resolve(path)));
            } catch (IOException error) {
                throw new IllegalStateException(
                        "adapter guard could not read compiled class " + path, error);
            }
        }
        return references;
    }

    /**
     * Minimal constant-pool reader collecting every referenced class name in internal form.
     * Only the constant pool is parsed; method bodies are never interpreted.
     */
    private static Set<String> referencedClasses(Path classFile) throws IOException {
        try (InputStream input = Files.newInputStream(classFile);
             DataInputStream data = new DataInputStream(input)) {
            if (data.readInt() != 0xCAFEBABE) {
                throw new IllegalStateException("not a class file: " + classFile);
            }
            data.readShort();
            data.readShort();
            int count = data.readShort() & 0xFFFF;
            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = data.readByte() & 0xFF;
                switch (tag) {
                    case 1 -> {
                        int length = data.readShort() & 0xFFFF;
                        byte[] bytes = new byte[length];
                        data.readFully(bytes);
                        utf8[index] = new String(bytes, StandardCharsets.UTF_8);
                    }
                    case 3, 4 -> data.readInt();
                    case 5, 6 -> {
                        data.readLong();
                        index++;
                    }
                    case 7 -> classNameIndex[index] = data.readShort() & 0xFFFF;
                    case 8, 16, 19, 20 -> data.readShort();
                    case 9, 10, 11, 12, 17, 18 -> data.skipBytes(4);
                    case 15 -> data.skipBytes(3);
                    default -> throw new IllegalStateException(
                            "unknown constant pool tag " + tag + " in " + classFile);
                }
            }
            Set<String> names = new HashSet<>();
            for (int index = 1; index < count; index++) {
                if (classNameIndex[index] != 0) {
                    names.add(utf8[classNameIndex[index]]);
                }
            }
            // Field, method-signature, and annotation types live only as Utf8 descriptors
            // (no CONSTANT_Class entry), so descriptor-embedded object types are collected
            // too; without this a field-typed controller reference would slip through.
            for (int index = 1; index < count; index++) {
                if (utf8[index] != null) {
                    names.addAll(descriptorTypes(utf8[index]));
                }
            }
            return names;
        }
    }

    private static final java.util.regex.Pattern DESCRIPTOR_TYPE =
            java.util.regex.Pattern.compile("L(org/km/llmwiki/[A-Za-z0-9/$]+);");

    private static Set<String> descriptorTypes(String constant) {
        Set<String> names = new HashSet<>();
        java.util.regex.Matcher matcher = DESCRIPTOR_TYPE.matcher(constant);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
