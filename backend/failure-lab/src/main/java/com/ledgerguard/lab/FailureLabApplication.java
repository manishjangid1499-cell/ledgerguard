package com.ledgerguard.lab;

import com.ledgerguard.LedgerGuardApplication;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.lab.adapter.HttpProviderTestAdapter;
import com.ledgerguard.lab.engine.LabRunCoordinator;
import com.ledgerguard.lab.engine.ScenarioRegistry;
import com.ledgerguard.lab.guard.LabDatabaseTarget;
import com.ledgerguard.lab.scenarios.RealCorruptedSnapshotScenario;
import com.ledgerguard.lab.scenarios.RealOpposingTransfersScenario;
import com.ledgerguard.lab.scenarios.RealTimeoutAfterCommitScenario;
import com.ledgerguard.lab.scenarios.RealWebhookRaceScenario;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.payout.application.PayoutService;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.api.ProviderWebhookController;
import com.ledgerguard.provider.application.ProviderStatusPollingService;
import com.ledgerguard.reconciliation.application.SnapshotAutoRepairService;
import com.ledgerguard.reconciliation.application.SnapshotConsistencyChecker;
import com.ledgerguard.transfer.application.TransferService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
@Import(LedgerGuardApplication.class)
public class FailureLabApplication {

    private static final Logger log = LoggerFactory.getLogger(FailureLabApplication.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static PostgreSQLContainer<?> postgresContainer;
    private static KafkaContainer kafkaContainer;
    private static HttpProviderTestAdapter httpProviderAdapter;
    private static LabDatabaseTarget labTarget;
    private static String runtimeWebhookSecret;

    public static void main(String[] args) {
        if (!isLabEnabled(args)) {
            log.error("[FAIL-CLOSED] Failure Lab runtime is disabled by default (ledgerguard.lab.enabled=false). " +
                    "Ephemeral Testcontainers and destructive lab endpoints will not start. " +
                    "Pass --ledgerguard.lab.enabled=true to activate.");
            System.err.println("[FAIL-CLOSED] Failure Lab runtime is disabled by default (ledgerguard.lab.enabled=false). " +
                    "Pass --ledgerguard.lab.enabled=true to activate.");
            System.exit(1);
            return;
        }

        log.info("Starting Failure Lab Ephemeral Infrastructure...");

        byte[] pwBytes = new byte[24];
        SECURE_RANDOM.nextBytes(pwBytes);
        String dbPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(pwBytes);

        postgresContainer = new PostgreSQLContainer<>("postgres:17.11-alpine")
                .withDatabaseName("ledgerguard_lab_ephemeral")
                .withUsername("lab_user")
                .withPassword(dbPassword)
                .withCommand("postgres", "-c", "max_connections=300");
        postgresContainer.start();

        kafkaContainer = new KafkaContainer("apache/kafka:4.3.1");
        kafkaContainer.start();

        try {
            httpProviderAdapter = new HttpProviderTestAdapter();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start HTTP provider test adapter", e);
        }

        labTarget = LabDatabaseTarget.authorizeEphemeral(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getDatabaseName(),
                LabDatabaseTarget.generateOwnershipToken()
        );

        byte[] jwtBytes = new byte[32];
        SECURE_RANDOM.nextBytes(jwtBytes);
        String jwtSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(jwtBytes);

        byte[] whBytes = new byte[32];
        SECURE_RANDOM.nextBytes(whBytes);
        runtimeWebhookSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(whBytes);

        SpringApplication app = new SpringApplication(FailureLabApplication.class);
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", postgresContainer.getJdbcUrl());
        props.put("spring.datasource.username", postgresContainer.getUsername());
        props.put("spring.datasource.password", postgresContainer.getPassword());
        props.put("spring.datasource.hikari.maximum-pool-size", 20);
        props.put("spring.datasource.hikari.minimum-idle", 2);
        props.put("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
        props.put("ledgerguard.security.jwt.secret", jwtSecret);
        props.put("ledgerguard.psp.webhook.secret", runtimeWebhookSecret);
        props.put("ledgerguard.psp.base-url", httpProviderAdapter.getBaseUrl());
        props.put("ledgerguard.psp.connect-timeout-ms", 1000);
        props.put("ledgerguard.psp.read-timeout-ms", 1000);
        props.put("ledgerguard.resilience.retry.create.max-attempts", 3);
        props.put("ledgerguard.resilience.retry.create.initial-backoff", "30ms");
        props.put("ledgerguard.resilience.retry.create.max-backoff", "60ms");
        props.put("ledgerguard.resilience.retry.create.multiplier", "1.0");
        props.put("ledgerguard.psp.polling.enabled", "false");
        props.put("ledgerguard.lab.enabled", "true");

        app.setDefaultProperties(props);
        app.run(args);

        log.info("Failure Lab started successfully on http://127.0.0.1:8083");
    }

    @Bean
    public HttpProviderTestAdapter httpProviderTestAdapter() {
        if (httpProviderAdapter == null) {
            try {
                httpProviderAdapter = new HttpProviderTestAdapter();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return httpProviderAdapter;
    }

    @Bean
    public LabDatabaseTarget labDatabaseTarget() {
        if (labTarget == null) {
            labTarget = LabDatabaseTarget.authorizeEphemeral(
                    "jdbc:postgresql://127.0.0.1:5432/lab",
                    "lab",
                    LabDatabaseTarget.generateOwnershipToken()
            );
        }
        return labTarget;
    }

    @Bean
    public ScenarioRegistry scenarioRegistry(
            TransferService transferService,
            PayoutService payoutService,
            ProviderStatusPollingService pollingService,
            SnapshotConsistencyChecker consistencyChecker,
            SnapshotAutoRepairService autoRepairService,
            ProviderWebhookController webhookController,
            LedgerPostingService postingService,
            UserRepository userRepository,
            LedgerAccountRepository accountRepository,
            PayoutRepository payoutRepository,
            BalanceHoldRepository balanceHoldRepository,
            FundingOperationRepository fundingOperationRepository,
            JdbcTemplate jdbcTemplate,
            HttpProviderTestAdapter providerAdapter,
            LabDatabaseTarget target,
            @Value("${ledgerguard.psp.webhook.secret:}") String webhookSecret
    ) {
        String effectiveSecret = (webhookSecret != null && !webhookSecret.isBlank())
                ? webhookSecret
                : (runtimeWebhookSecret != null ? runtimeWebhookSecret : "default-lab-secret");
        ScenarioRegistry registry = new ScenarioRegistry();
        registry.register(new RealOpposingTransfersScenario(
                transferService, postingService, userRepository, accountRepository
        ));
        registry.register(new RealTimeoutAfterCommitScenario(
                payoutService, pollingService, payoutRepository, balanceHoldRepository,
                userRepository, accountRepository, postingService, jdbcTemplate, providerAdapter
        ));
        registry.register(new RealCorruptedSnapshotScenario(
                consistencyChecker, autoRepairService, userRepository, accountRepository,
                postingService, jdbcTemplate, target
        ));
        registry.register(new RealWebhookRaceScenario(
                webhookController, fundingOperationRepository, userRepository, accountRepository, effectiveSecret
        ));
        return registry;
    }

    @Bean
    public LabRunCoordinator labRunCoordinator(ScenarioRegistry scenarioRegistry, DataSource dataSource) {
        return new LabRunCoordinator(scenarioRegistry, dataSource);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain failureLabSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/lab/**")
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @PreDestroy
    public void cleanup() {
        if (httpProviderAdapter != null) {
            httpProviderAdapter.stop();
        }
        if (postgresContainer != null && postgresContainer.isRunning()) {
            postgresContainer.stop();
        }
        if (kafkaContainer != null && kafkaContainer.isRunning()) {
            kafkaContainer.stop();
        }
    }

    static boolean isLabEnabled(String[] args) {
        if (Boolean.parseBoolean(System.getProperty("ledgerguard.lab.enabled"))
                || Boolean.parseBoolean(System.getenv("LEDGERGUARD_LAB_ENABLED"))) {
            return true;
        }
        if (args != null) {
            for (String arg : args) {
                if ("--ledgerguard.lab.enabled=true".equalsIgnoreCase(arg)
                        || "ledgerguard.lab.enabled=true".equalsIgnoreCase(arg)
                        || "-Dledgerguard.lab.enabled=true".equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }
}
