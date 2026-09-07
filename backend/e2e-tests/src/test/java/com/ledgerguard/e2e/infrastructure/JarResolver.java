package com.ledgerguard.e2e.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;

public final class JarResolver {

    private static final Path WORKSPACE_ROOT = findWorkspaceRoot();

    private JarResolver() {}

    public static Path resolveApiJar() {
        Path jar = WORKSPACE_ROOT.resolve("backend/ledgerguard-api/target/ledgerguard-api-0.1.0-SNAPSHOT-exec.jar");
        validateExecutableJar(jar, "ledgerguard-api");
        return jar;
    }

    public static Path resolvePspJar() {
        Path jar = WORKSPACE_ROOT.resolve("backend/psp-simulator/target/psp-simulator-0.1.0-SNAPSHOT.jar");
        validateExecutableJar(jar, "psp-simulator");
        return jar;
    }

    public static Path resolveNotificationWorkerJar() {
        Path jar = WORKSPACE_ROOT.resolve("backend/notification-worker/target/notification-worker-0.1.0-SNAPSHOT.jar");
        validateExecutableJar(jar, "notification-worker");
        return jar;
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
