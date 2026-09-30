package com.ledgerguard.e2e.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class JarResolver {

    private static final Path WORKSPACE_ROOT = findWorkspaceRoot();

    private JarResolver() {}

    public static Path resolveApiJar() {
        Path targetDir = WORKSPACE_ROOT.resolve("backend/ledgerguard-api/target");
        Path jar = resolveCandidateJar(targetDir, "ledgerguard-api-", true);
        validateExecutableJar(jar, "ledgerguard-api");
        return jar;
    }

    public static Path resolvePspJar() {
        Path targetDir = WORKSPACE_ROOT.resolve("backend/psp-simulator/target");
        Path jar = resolveCandidateJar(targetDir, "psp-simulator-", false);
        validateExecutableJar(jar, "psp-simulator");
        return jar;
    }

    public static Path resolveNotificationWorkerJar() {
        Path targetDir = WORKSPACE_ROOT.resolve("backend/notification-worker/target");
        Path jar = resolveCandidateJar(targetDir, "notification-worker-", false);
        validateExecutableJar(jar, "notification-worker");
        return jar;
    }

    static Path resolveCandidateJar(Path targetDir, String artifactPrefix, boolean requireExecClassifier) {
        String pattern = requireExecClassifier ? artifactPrefix + "*-exec.jar" : artifactPrefix + "*.jar";

        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            throw new IllegalStateException("Target directory does not exist or is not a directory: " +
                    targetDir.toAbsolutePath() + " (expected to contain executable JAR matching '" + pattern + "'). " +
                    "Ensure module packaging precedes E2E test execution.");
        }

        List<Path> candidates;
        try (java.util.stream.Stream<Path> stream = Files.list(targetDir)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isEligibleJar(path.getFileName().toString(), artifactPrefix, requireExecClassifier))
                    .sorted(java.util.Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan directory for executable JAR: " + targetDir.toAbsolutePath(), e);
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("No executable JAR matching pattern '" + pattern +
                    "' found in target directory: " + targetDir.toAbsolutePath() +
                    ". Ensure module packaging precedes E2E test execution (e.g. './mvnw package -DskipTests').");
        }

        if (candidates.size() > 1) {
            List<String> filenames = candidates.stream()
                    .map(p -> p.getFileName().toString())
                    .toList();
            throw new IllegalStateException("Multiple candidate executable JARs found matching pattern '" + pattern +
                    "' in " + targetDir.toAbsolutePath() + ": " + filenames +
                    ". Clean the target directory before testing to resolve ambiguity.");
        }

        return candidates.get(0);
    }

    static boolean isEligibleJar(String filename, String artifactPrefix, boolean requireExecClassifier) {
        if (!filename.startsWith(artifactPrefix) || !filename.endsWith(".jar")) {
            return false;
        }
        if (filename.endsWith(".jar.original")
                || filename.endsWith("-sources.jar")
                || filename.endsWith("-javadoc.jar")
                || filename.endsWith("-tests.jar")) {
            return false;
        }
        if (requireExecClassifier) {
            return filename.endsWith("-exec.jar");
        } else {
            return !filename.endsWith("-exec.jar");
        }
    }

    private static void validateExecutableJar(Path jarPath, String moduleName) {
        if (!Files.exists(jarPath) || !Files.isRegularFile(jarPath)) {
            throw new IllegalStateException("Required executable JAR for " + moduleName + " not found at: " +
                    jarPath.toAbsolutePath() + ". Ensure module packaging precedes E2E test execution.");
        }
        try {
            long size = Files.size(jarPath);
            if (size < 1_000_000) {
                throw new IllegalStateException("JAR for " + moduleName + " is too small (" + size +
                        " bytes) to be an executable fat JAR: " + jarPath.toAbsolutePath());
            }
            try (JarFile jf = new JarFile(jarPath.toFile())) {
                if (jf.getManifest() == null || jf.getManifest().getMainAttributes().getValue("Main-Class") == null) {
                    throw new IllegalStateException("JAR for " + moduleName + " lacks Main-Class in manifest: " +
                            jarPath.toAbsolutePath());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to validate JAR for " + moduleName + " at: " + jarPath, e);
        }
    }

    private static Path findWorkspaceRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.exists(current.resolve("backend/ledgerguard-api"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }
}
