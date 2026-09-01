package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.platform.commons.annotation.Testable;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministically verifies that every executable test class belongs to a Maven test tier.
 *
 * <p>The scan uses compiled test classes and JUnit's {@link Testable} marker rather than source
 * filenames or class-name conventions. Tags are resolved from direct, composed, inherited, and
 * enclosing-class annotations so nested tests and shared integration annotations are covered.
 */
public final class TestTierCoverageGuard {

    public static final Set<String> REQUIRED_TIERS = Set.of("unit", "contract", "integration");

    /**
     * Deliberately empty. A full-only test requires an entry here and a matching explanation in
     * docs/development/testing.md; an unclassified test must never be silently accepted.
     */
    public static final Set<String> FULL_ONLY_TEST_WHITELIST = Set.of();

    private static final String TEST_PACKAGE = "org.km.llmwiki";
    private static final Set<String> DIRECT_EXECUTABLE_ANNOTATIONS = Set.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.params.ParameterizedTest");

    private TestTierCoverageGuard() {
    }

    public static void assertComplete() {
        List<TestClass> discovered = scan();
        Set<String> discoveredNames = discovered.stream()
                .map(TestClass::className)
                .collect(Collectors.toSet());
        Set<String> staleWhitelist = new HashSet<>(FULL_ONLY_TEST_WHITELIST);
        staleWhitelist.removeAll(discoveredNames);

        List<String> unclassified = discovered.stream()
                .filter(testClass -> !FULL_ONLY_TEST_WHITELIST.contains(testClass.className()))
                .filter(testClass -> testClass.tiers().stream().noneMatch(REQUIRED_TIERS::contains))
                .map(TestClass::className)
                .sorted()
                .toList();

        if (!staleWhitelist.isEmpty() || !unclassified.isEmpty()) {
            throw new IllegalStateException("Executable test tier coverage failed: stale whitelist="
                    + staleWhitelist + ", unclassified=" + unclassified);
        }
    }

    public static List<TestClass> scan() {
        Path testClasses = testClassesRoot();
        Path packageRoot = testClasses.resolve(TEST_PACKAGE.replace('.', '/'));
        if (!Files.isDirectory(packageRoot)) {
            throw new IllegalStateException("Compiled test package is missing: " + packageRoot);
        }

        try (var paths = Files.walk(packageRoot)) {
            return paths.filter(path -> path.toString().endsWith(".class"))
                    .map(testClasses::relativize)
                    .map(Path::toString)
                    .map(path -> path.substring(0, path.length() - ".class".length())
                            .replace(Path.of("").getFileSystem().getSeparator(), "."))
                    .filter(name -> !name.endsWith("module-info") && !name.endsWith("package-info"))
                    .sorted()
                    .map(TestTierCoverageGuard::load)
                    .filter(TestTierCoverageGuard::isExecutableTestClass)
                    .map(type -> new TestClass(type.getName(), type, tiersOf(type)))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not scan compiled test classes", exception);
        }
    }

    public static boolean isExecutableTestClass(Class<?> type) {
        return !type.isAnnotation()
                && !type.isEnum()
                && !type.isInterface()
                && !type.isSynthetic()
                && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                && hasExecutableTestMethod(type);
    }

    public static Set<String> tiersOf(Class<?> type) {
        Set<String> tiers = new HashSet<>();
        collectClassTags(type, tiers, new HashSet<>());
        return Set.copyOf(tiers);
    }

    private static Path testClassesRoot() {
        try {
            return Path.of(TestTierCoverageGuard.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Could not locate compiled test classes", exception);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, TestTierCoverageGuard.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Could not load compiled test class " + name, exception);
        }
    }

    private static boolean hasExecutableTestMethod(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                for (Annotation annotation : method.getDeclaredAnnotations()) {
                    if (isExecutableAnnotation(annotation.annotationType(), new HashSet<>())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isExecutableAnnotation(Class<? extends Annotation> annotationType,
                                                   Set<Class<?>> visited) {
        if (!visited.add(annotationType)) {
            return false;
        }
        if (DIRECT_EXECUTABLE_ANNOTATIONS.contains(annotationType.getName())
                || annotationType.isAnnotationPresent(Testable.class)) {
            return true;
        }
        for (Annotation metaAnnotation : annotationType.getDeclaredAnnotations()) {
            if (isExecutableAnnotation(metaAnnotation.annotationType(), visited)) {
                return true;
            }
        }
        return false;
    }

    private static void collectClassTags(Class<?> type, Set<String> tiers,
                                         Set<Class<?>> visitedAnnotationTypes) {
        if (type == null || type == Object.class) {
            return;
        }
        for (Annotation annotation : type.getDeclaredAnnotations()) {
            collectTags(annotation, tiers, visitedAnnotationTypes);
        }
        // Explicit traversal keeps this correct even when a project annotation loses @Inherited.
        collectClassTags(type.getSuperclass(), tiers, visitedAnnotationTypes);
        collectClassTags(type.getEnclosingClass(), tiers, visitedAnnotationTypes);
    }

    private static void collectTags(Annotation annotation, Set<String> tiers,
                                    Set<Class<?>> visitedAnnotationTypes) {
        if (annotation instanceof Tag tag) {
            tiers.add(tag.value());
            return;
        }
        if (annotation instanceof Tags tags) {
            for (Tag tag : tags.value()) {
                tiers.add(tag.value());
            }
            return;
        }
        if (!visitedAnnotationTypes.add(annotation.annotationType())) {
            return;
        }
        for (Annotation metaAnnotation : annotation.annotationType().getDeclaredAnnotations()) {
            collectTags(metaAnnotation, tiers, visitedAnnotationTypes);
        }
    }

    public record TestClass(String className, Class<?> type, Set<String> tiers) {
        public TestClass {
            tiers = Set.copyOf(tiers);
        }
    }
}
