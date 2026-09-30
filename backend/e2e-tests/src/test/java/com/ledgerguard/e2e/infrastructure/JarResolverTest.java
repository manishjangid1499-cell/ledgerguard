package com.ledgerguard.e2e.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JarResolverTest {

    @Test
    @DisplayName("Resolves single valid API executable candidate while ignoring plain JAR and sources/javadoc")
    void resolveCandidateJar_withOneValidApiExecutable_returnsPath(@TempDir Path tempDir) throws IOException {
        Path execJar = Files.createFile(tempDir.resolve("ledgerguard-api-1.1.0-exec.jar"));
        Files.createFile(tempDir.resolve("ledgerguard-api-1.1.0.jar"));
        Files.createFile(tempDir.resolve("ledgerguard-api-1.1.0-sources.jar"));
        Files.createFile(tempDir.resolve("ledgerguard-api-1.1.0-javadoc.jar"));

        Path resolved = JarResolver.resolveCandidateJar(tempDir, "ledgerguard-api-", true);

        assertThat(resolved).isEqualTo(execJar);
    }

    @Test
    @DisplayName("Resolves single valid PSP candidate while ignoring .jar.original and classified artifacts")
    void resolveCandidateJar_withOneValidPspCandidate_returnsPath(@TempDir Path tempDir) throws IOException {
        Path pspJar = Files.createFile(tempDir.resolve("psp-simulator-1.1.0.jar"));
        Files.createFile(tempDir.resolve("psp-simulator-1.1.0.jar.original"));
        Files.createFile(tempDir.resolve("psp-simulator-1.1.0-sources.jar"));
        Files.createFile(tempDir.resolve("psp-simulator-1.1.0-javadoc.jar"));
        Files.createFile(tempDir.resolve("psp-simulator-1.1.0-tests.jar"));

        Path resolved = JarResolver.resolveCandidateJar(tempDir, "psp-simulator-", false);

        assertThat(resolved).isEqualTo(pspJar);
    }

    @Test
    @DisplayName("Resolves single valid notification worker candidate")
    void resolveCandidateJar_withOneValidWorkerCandidate_returnsPath(@TempDir Path tempDir) throws IOException {
        Path workerJar = Files.createFile(tempDir.resolve("notification-worker-1.1.0.jar"));
        Files.createFile(tempDir.resolve("notification-worker-1.1.0.jar.original"));

        Path resolved = JarResolver.resolveCandidateJar(tempDir, "notification-worker-", false);

        assertThat(resolved).isEqualTo(workerJar);
    }

    @Test
    @DisplayName("Throws actionable exception when zero candidate JARs exist")
    void resolveCandidateJar_whenZeroCandidates_throwsActionableException(@TempDir Path tempDir) {
        assertThatThrownBy(() -> JarResolver.resolveCandidateJar(tempDir, "ledgerguard-api-", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No executable JAR matching pattern 'ledgerguard-api-*-exec.jar' found in target directory")
                .hasMessageContaining("Ensure module packaging precedes E2E test execution");
    }

    @Test
    @DisplayName("Throws explicit exception listing candidate filenames when multiple candidates exist")
    void resolveCandidateJar_whenMultipleCandidates_throwsExplicitExceptionListingFilenames(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("psp-simulator-1.0.0.jar"));
        Files.createFile(tempDir.resolve("psp-simulator-1.1.0.jar"));

        assertThatThrownBy(() -> JarResolver.resolveCandidateJar(tempDir, "psp-simulator-", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple candidate executable JARs found matching pattern 'psp-simulator-*.jar'")
                .hasMessageContaining("psp-simulator-1.0.0.jar")
                .hasMessageContaining("psp-simulator-1.1.0.jar")
                .hasMessageContaining("Clean the target directory before testing to resolve ambiguity");
    }

    @Test
    @DisplayName("Rejects API plain library JAR when requireExecClassifier is true")
    void resolveCandidateJar_rejectsApiPlainLibraryJar(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("ledgerguard-api-1.1.0.jar"));

        assertThatThrownBy(() -> JarResolver.resolveCandidateJar(tempDir, "ledgerguard-api-", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No executable JAR matching pattern 'ledgerguard-api-*-exec.jar'");
    }

    @Test
    @DisplayName("Rejects non-existent target directory with clear diagnostic")
    void resolveCandidateJar_whenTargetDirDoesNotExist_throwsActionableException(@TempDir Path tempDir) {
        Path missingDir = tempDir.resolve("non-existent-target");

        assertThatThrownBy(() -> JarResolver.resolveCandidateJar(missingDir, "ledgerguard-api-", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Target directory does not exist or is not a directory")
                .hasMessageContaining("Ensure module packaging precedes E2E test execution");
    }

    @Test
    @DisplayName("Validates eligibility filtering rules across all artifact categories")
    void isEligibleJar_validatesClassificationRules() {
        // API executable requires -exec.jar
        assertThat(JarResolver.isEligibleJar("ledgerguard-api-1.1.0-exec.jar", "ledgerguard-api-", true)).isTrue();
        assertThat(JarResolver.isEligibleJar("ledgerguard-api-1.1.0.jar", "ledgerguard-api-", true)).isFalse();
        assertThat(JarResolver.isEligibleJar("ledgerguard-api-1.1.0-sources.jar", "ledgerguard-api-", true)).isFalse();
        assertThat(JarResolver.isEligibleJar("ledgerguard-api-1.1.0-javadoc.jar", "ledgerguard-api-", true)).isFalse();
        assertThat(JarResolver.isEligibleJar("ledgerguard-api-1.1.0-tests.jar", "ledgerguard-api-", true)).isFalse();
        assertThat(JarResolver.isEligibleJar("ledgerguard-api-1.1.0.jar.original", "ledgerguard-api-", true)).isFalse();

        // Worker/PSP standard executable excludes -exec.jar and classified jars
        assertThat(JarResolver.isEligibleJar("psp-simulator-1.1.0.jar", "psp-simulator-", false)).isTrue();
        assertThat(JarResolver.isEligibleJar("psp-simulator-1.1.0-exec.jar", "psp-simulator-", false)).isFalse();
        assertThat(JarResolver.isEligibleJar("psp-simulator-1.1.0-sources.jar", "psp-simulator-", false)).isFalse();
        assertThat(JarResolver.isEligibleJar("psp-simulator-1.1.0-javadoc.jar", "psp-simulator-", false)).isFalse();
        assertThat(JarResolver.isEligibleJar("psp-simulator-1.1.0-tests.jar", "psp-simulator-", false)).isFalse();
        assertThat(JarResolver.isEligibleJar("psp-simulator-1.1.0.jar.original", "psp-simulator-", false)).isFalse();

        // Unrelated prefixes or extensions
        assertThat(JarResolver.isEligibleJar("other-module-1.1.0.jar", "psp-simulator-", false)).isFalse();
        assertThat(JarResolver.isEligibleJar("psp-simulator-1.1.0.war", "psp-simulator-", false)).isFalse();
    }
}
