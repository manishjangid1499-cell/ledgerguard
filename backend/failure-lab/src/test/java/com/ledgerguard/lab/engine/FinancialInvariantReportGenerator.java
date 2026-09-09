package com.ledgerguard.lab.engine;

import tools.jackson.databind.ObjectMapper;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only reporting utility that formats authoritative ScenarioRunResult
 * instances into versioned JSON and Markdown artifacts for CI.
 *
 * This utility does NOT execute scenarios, query PostgreSQL, or evaluate
 * financial rules independently; it strictly formats existing authoritative results.
 */
public final class FinancialInvariantReportGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FinancialInvariantReportGenerator() {
    }

    public record InvariantReport(
            String name,
            boolean passed,
            String expected,
            String actual,
            String message
    ) {}

    public record ScenarioReport(
            String scenarioId,
            String status,
            long durationMs,
            boolean allInvariantsPassed,
            String errorMessage,
            String ledgerDebitTotalAtAudit,
            String ledgerCreditTotalAtAudit,
            String netLedgerDeltaAtAudit,
            List<InvariantReport> invariants
    ) {}

    public record FinancialIntegrityReport(
            int schemaVersion,
            String generatedAt,
            String commitSha,
            String verdictScope,
            String scenarioInvariantVerdict,
            int scenarioCount,
            List<ScenarioReport> scenarios
    ) {}

    /**
     * Generates both financial-integrity-report.json and financial-integrity-summary.md
     * in the appropriate target directory based on current working directory.
     */
    public static void generate(List<ScenarioRunResult> results) throws IOException {
        Path targetDir = resolveTargetDirectory();
        Files.createDirectories(targetDir);

        String commitSha = System.getenv("GITHUB_SHA");
        if (commitSha == null || commitSha.isBlank()) {
            commitSha = "LOCAL";
        }

        String generatedAt = Instant.now().toString();

        Set<ScenarioId> expectedScenarios = Set.of(
                ScenarioId.OPPOSING_TRANSFERS,
                ScenarioId.TIMEOUT_AFTER_COMMIT,
                ScenarioId.CORRUPTED_SNAPSHOT,
                ScenarioId.WEBHOOK_RACE
        );

        Set<ScenarioId> seenScenarios = new HashSet<>();
        boolean duplicateFound = false;
        for (ScenarioRunResult res : results) {
            if (!seenScenarios.add(res.scenarioId())) {
                duplicateFound = true;
            }
        }

        boolean exactSetMatches = results.size() == 4
                && !duplicateFound
                && seenScenarios.equals(expectedScenarios);

        boolean allPassed = exactSetMatches && results.stream().allMatch(r ->
                r.status() == ScenarioStatus.PASSED
                        && r.isAllInvariantsPassed()
                        && r.errorMessage() == null
        );
        String scenarioInvariantVerdict = allPassed ? "VERIFIED" : "FAILED";
        String verdictScope = "SCENARIO_RUNNER_INVARIANTS";

        List<ScenarioReport> scenarioReports = new ArrayList<>();
        for (ScenarioRunResult res : results) {
            String debitAtAudit = "N/A";
            String creditAtAudit = "N/A";
            String netDeltaAtAudit = "N/A";

            List<InvariantReport> invReports = new ArrayList<>();
            for (InvariantCheckResult inv : res.invariantResults()) {
                invReports.add(new InvariantReport(
                        inv.invariantName(),
                        inv.passed(),
                        inv.expectedValue(),
                        inv.actualValue(),
                        inv.message()
                ));

                if ("GLOBAL_ZERO_SUM".equals(inv.invariantName())) {
                    debitAtAudit = inv.expectedValue();
                    creditAtAudit = inv.actualValue();
                    try {
                        long d = Long.parseLong(debitAtAudit);
                        long c = Long.parseLong(creditAtAudit);
                        netDeltaAtAudit = String.valueOf(d - c);
                    } catch (NumberFormatException ignored) {
                        netDeltaAtAudit = "N/A";
                    }
                }
            }

            scenarioReports.add(new ScenarioReport(
                    res.scenarioId().getKey(),
                    res.status().name(),
                    res.durationMs(),
                    res.isAllInvariantsPassed(),
                    res.errorMessage(),
                    debitAtAudit,
                    creditAtAudit,
                    netDeltaAtAudit,
                    invReports
            ));
        }

        FinancialIntegrityReport report = new FinancialIntegrityReport(
                1,
                generatedAt,
                commitSha,
                verdictScope,
                scenarioInvariantVerdict,
                results.size(),
                scenarioReports
        );

        // Write JSON artifact
        Path jsonFile = targetDir.resolve("financial-integrity-report.json");
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(jsonFile, json);

        // Write Markdown artifact
        Path mdFile = targetDir.resolve("financial-integrity-summary.md");
        String markdown = buildMarkdownSummary(report);
        Files.writeString(mdFile, markdown);
    }

    private static String buildMarkdownSummary(FinancialIntegrityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# LedgerGuard Financial Integrity CI\n\n");
        sb.append("**Scenario Invariant Verdict:** `").append(report.scenarioInvariantVerdict()).append("`  \n");
        sb.append("**Verdict Scope:** `").append(report.verdictScope()).append("`  \n");
        sb.append("**Generated At:** ").append(report.generatedAt()).append("  \n");
        sb.append("**Commit SHA:** `").append(report.commitSha()).append("`  \n");
        sb.append("**Scenarios Executed:** ").append(report.scenarioCount()).append("\n\n");
        sb.append("> **Note:** The scenario invariant verdict summarizes the four ScenarioRunner financial invariant results. The GitHub Actions financial-integrity job result is the authoritative gate because it also executes the four dedicated scenario JUnit tests.\n\n");

        sb.append("## Scenario Execution Summary\n\n");
        sb.append("| Scenario | Status | Duration | Invariant Checks Passed | Ledger Debit Total at Audit | Ledger Credit Total at Audit | Net Ledger Delta at Audit | Final Verdict |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");

        for (ScenarioReport s : report.scenarios()) {
            long passedCount = s.invariants().stream().filter(InvariantReport::passed).count();
            int totalCount = s.invariants().size();
            String checksPassed = passedCount + " / " + totalCount;
            String verdict = (s.status().equals("PASSED") && s.allInvariantsPassed()) ? "VERIFIED" : "FAILED";

            sb.append("| `").append(s.scenarioId()).append("` | ")
                    .append(s.status()).append(" | ")
                    .append(s.durationMs()).append("ms | ")
                    .append(checksPassed).append(" | ")
                    .append(s.ledgerDebitTotalAtAudit()).append(" | ")
                    .append(s.ledgerCreditTotalAtAudit()).append(" | ")
                    .append(s.netLedgerDeltaAtAudit()).append(" | ")
                    .append("**").append(verdict).append("** |\n");
        }

        sb.append("\n## Detailed Invariant Audit\n\n");
        sb.append("| Scenario | Invariant | Passed | Expected | Actual | Message |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");

        for (ScenarioReport s : report.scenarios()) {
            for (InvariantReport inv : s.invariants()) {
                String passText = inv.passed() ? "YES" : "NO";
                sb.append("| `").append(s.scenarioId()).append("` | `")
                        .append(inv.name()).append("` | ")
                        .append(passText).append(" | ")
                        .append(inv.expected()).append(" | ")
                        .append(inv.actual()).append(" | ")
                        .append(sanitizeMessage(inv.message())).append(" |\n");
            }
        }

        return sb.toString();
    }

    private static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.replace("|", "\\|").replace("\n", " ");
    }

    private static Path resolveTargetDirectory() {
        if (Files.exists(Path.of("backend/failure-lab"))) {
            return Path.of("backend/failure-lab/target");
        }
        return Path.of("target");
    }
}
