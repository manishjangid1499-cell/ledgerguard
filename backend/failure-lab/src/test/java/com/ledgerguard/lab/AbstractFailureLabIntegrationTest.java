package com.ledgerguard.lab;

import com.ledgerguard.LedgerGuardApplication;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.lab.adapter.HttpProviderTestAdapter;
import com.ledgerguard.lab.guard.LabDatabaseTarget;
import com.ledgerguard.lab.invariants.FinancialInvariantOracle;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.payout.application.PayoutService;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.api.ProviderWebhookController;
import com.ledgerguard.provider.application.ProviderStatusPollingService;
import com.ledgerguard.provider.application.ProviderWebhookService;
import com.ledgerguard.reconciliation.application.SnapshotAutoRepairService;
import com.ledgerguard.reconciliation.application.SnapshotConsistencyChecker;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import com.ledgerguard.transfer.application.TransferService;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

@SpringBootTest(classes = LedgerGuardApplication.class)
@ActiveProfiles("test")
public abstract class AbstractFailureLabIntegrationTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String RUNTIME_DB_PASSWORD = generateRuntimeSecret();

    public static final PostgreSQLContainer<?> POSTGRES_CONTAINER =
            new PostgreSQLContainer<>("postgres:17.11-alpine")
                    .withDatabaseName("ledgerguard_lab_test")
                    .withUsername("lab_user")
                    .withPassword(RUNTIME_DB_PASSWORD)
                    .withCommand("postgres", "-c", "max_connections=300");

    public static final KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer("apache/kafka:4.3.1");

    public static final HttpProviderTestAdapter HTTP_PROVIDER_ADAPTER;

    private static final String RUNTIME_JWT_SECRET = generateRuntimeSecret();
    public static final String RUNTIME_WEBHOOK_SECRET = generateRuntimeSecret();
    public static final LabDatabaseTarget LAB_TARGET;

    private static String generateRuntimeSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static {
        try {
            HTTP_PROVIDER_ADAPTER = new HttpProviderTestAdapter();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start HTTP provider test adapter", e);
        }

        POSTGRES_CONTAINER.start();
        KAFKA_CONTAINER.start();

        LAB_TARGET = LabDatabaseTarget.authorizeEphemeral(
                POSTGRES_CONTAINER.getJdbcUrl(),
                POSTGRES_CONTAINER.getDatabaseName(),
                LabDatabaseTarget.generateOwnershipToken()
        );
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 15);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 2);
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("ledgerguard.security.jwt.secret", () -> RUNTIME_JWT_SECRET);
        registry.add("ledgerguard.psp.webhook.secret", () -> RUNTIME_WEBHOOK_SECRET);
        registry.add("ledgerguard.psp.base-url", HTTP_PROVIDER_ADAPTER::getBaseUrl);
        registry.add("ledgerguard.psp.connect-timeout-ms", () -> 1000);
        registry.add("ledgerguard.psp.read-timeout-ms", () -> 1000);
        registry.add("ledgerguard.resilience.retry.create.max-attempts", () -> 3);
        registry.add("ledgerguard.resilience.retry.create.initial-backoff", () -> "30ms");
        registry.add("ledgerguard.resilience.retry.create.max-backoff", () -> "60ms");
        registry.add("ledgerguard.resilience.retry.create.multiplier", () -> "1.0");
        registry.add("ledgerguard.psp.polling.enabled", () -> "false");
    }

    @AfterAll
    static void tearDownAll() {
        // HTTP_PROVIDER_ADAPTER.stop();
    }

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected PspClient pspClient;

    @Autowired
    protected TransferService transferService;

    @Autowired
    protected PayoutService payoutService;

    @Autowired
    protected ProviderStatusPollingService pollingService;

    @Autowired
    protected SnapshotConsistencyChecker snapshotConsistencyChecker;

    @Autowired
    protected SnapshotAutoRepairService autoRepairService;

    @Autowired
    protected ProviderWebhookService webhookService;

    @Autowired
    protected ProviderWebhookController webhookController;

    @Autowired
    protected LedgerPostingService ledgerPostingService;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    protected LedgerBalanceSnapshotRepository snapshotRepository;

    @Autowired
    protected PayoutRepository payoutRepository;

    @Autowired
    protected BalanceHoldRepository balanceHoldRepository;

    @Autowired
    protected FundingOperationRepository fundingOperationRepository;

    @Autowired
    protected ReconciliationCaseRepository reconciliationCaseRepository;

    @Autowired
    protected ReconciliationItemRepository reconciliationItemRepository;

    protected final FinancialInvariantOracle oracle = new FinancialInvariantOracle();
}
