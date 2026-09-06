package com.ledgerguard.lab.scenarios;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.engine.ChaosScenario;
import com.ledgerguard.lab.fault.SnapshotFaultInjector;
import com.ledgerguard.lab.guard.LabDatabaseTarget;
import com.ledgerguard.lab.invariants.FinancialInvariantOracle;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.model.ScenarioStepEvent;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.reconciliation.api.SnapshotRepairResponse;
import com.ledgerguard.reconciliation.application.SnapshotAutoRepairService;
import com.ledgerguard.reconciliation.application.SnapshotConsistencyChecker;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real production scenario injecting snapshot drift and executing
 * real Phase 24 detection and Phase 25 auto-repair.
 */
public class RealCorruptedSnapshotScenario implements ChaosScenario {

    private final SnapshotConsistencyChecker consistencyChecker;
    private final SnapshotAutoRepairService autoRepairService;
    private final UserRepository userRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerPostingService postingService;
    private final JdbcTemplate jdbcTemplate;
    private final LabDatabaseTarget labTarget;
    private final FinancialInvariantOracle oracle = new FinancialInvariantOracle();

    public RealCorruptedSnapshotScenario(
            SnapshotConsistencyChecker consistencyChecker,
            SnapshotAutoRepairService autoRepairService,
            UserRepository userRepository,
            LedgerAccountRepository accountRepository,
            LedgerPostingService postingService,
            JdbcTemplate jdbcTemplate,
            LabDatabaseTarget labTarget
    ) {
        this.consistencyChecker = consistencyChecker;
        this.autoRepairService = autoRepairService;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.postingService = postingService;
        this.jdbcTemplate = jdbcTemplate;
        this.labTarget = labTarget;
    }

    @Override
    public ScenarioId getId() {
        return ScenarioId.CORRUPTED_SNAPSHOT;
    }

    @Override
    public String getDescription() {
        return "Deliberate snapshot drift detection via Level 2 reconciliation and auto-repair from immutable journals";
    }

    @Override
    public ScenarioRunResult execute(UUID runId, DataSource dataSource) throws Exception {
        Instant startTime = Instant.now();
        List<ScenarioStepEvent> timeline = new ArrayList<>();
        List<InvariantCheckResult> invariantResults = new ArrayList<>();

        timeline.add(ScenarioStepEvent.of("SETUP", "Seeding customer and OPS users, accounts, and valid initial posting"));

        UUID userId = UUID.randomUUID();
        UUID opsUserId = UUID.randomUUID();

        userRepository.save(new User(userId, "snapUser." + userId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        userRepository.save(new User(opsUserId, "snapOps." + opsUserId + "@lab.local", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));

        LedgerAccount customerAccount = accountRepository.save(LedgerAccount.createCustomerAccount(userId));
        LedgerAccount clearingAccount = accountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> accountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        long initialFunds = 50000L;
        postingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearingAccount.getId(), EntryDirection.DEBIT, Money.inr(initialFunds)),
                        new PostingLine(customerAccount.getId(), EntryDirection.CREDIT, Money.inr(initialFunds))
                )
        ));

        timeline.add(ScenarioStepEvent.of("INJECT_DRIFT", "Injecting deliberate balance corruption into snapshot via SnapshotFaultInjector"));
        long corruptedBalance = 10000L;
        int updated = SnapshotFaultInjector.corruptSnapshotBalance(labTarget, dataSource, customerAccount.getId(), corruptedBalance);
        if (updated != 1) {
            throw new IllegalStateException("Failed to inject snapshot drift: updated " + updated + " rows");
        }

        timeline.add(ScenarioStepEvent.of("RUN_DETECTION", "Executing real Phase 24 SnapshotConsistencyChecker"));
        UUID reconRunId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())",
                reconRunId
        );

        consistencyChecker.check(reconRunId);

        UUID caseId = jdbcTemplate.queryForObject(
                "SELECT rc.id FROM reconciliation_cases rc " +
                        "JOIN reconciliation_items ri ON ri.id = rc.reconciliation_item_id " +
                        "WHERE ri.reconciliation_run_id = ? AND ri.entity_id = ?",
                UUID.class, reconRunId, customerAccount.getId()
        );
        if (caseId == null) {
            throw new IllegalStateException("Reconciliation failed to generate case for snapshot mismatch!");
        }
        timeline.add(ScenarioStepEvent.of("DISCREPANCY_DETECTED", "Detected SNAPSHOT_MISMATCH case: " + caseId));

        timeline.add(ScenarioStepEvent.of("RUN_REPAIR", "Executing real Phase 25 SnapshotAutoRepairService"));
        SnapshotRepairResponse repairResponse = autoRepairService.repairSnapshot(caseId, opsUserId);
        timeline.add(ScenarioStepEvent.of("REPAIRED", "Repaired balance restored from " + repairResponse.previousBalanceMinor()
                + " to " + repairResponse.repairedBalanceMinor()));

        timeline.add(ScenarioStepEvent.of("ORACLE_AUDIT", "Running independent oracle verification"));
        try (Connection conn = dataSource.getConnection()) {
            invariantResults.addAll(oracle.checkJournalStructure(conn));
            invariantResults.addAll(oracle.checkDebitCreditEquality(conn));
            invariantResults.addAll(oracle.checkSnapshotIntegrity(conn, customerAccount.getId()));
            invariantResults.add(oracle.checkAvailableBalance(conn, customerAccount.getId()));
        }

        boolean allPassed = invariantResults.stream().allMatch(InvariantCheckResult::passed);
        Instant endTime = Instant.now();

        return new ScenarioRunResult(
                runId, getId(), allPassed ? ScenarioStatus.PASSED : ScenarioStatus.FAILED,
                startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                invariantResults, timeline, allPassed ? null : "Invariant check failed"
        );
    }
}
