package com.ledgerguard.e2e.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.testcontainers.kafka.KafkaContainer;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class E2EEnvironment {

    private static final Logger log = LoggerFactory.getLogger(E2EEnvironment.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static E2EEnvironment INSTANCE;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private final Network network;
    private final PostgreSQLContainer<?> postgres;
    private final KafkaContainer kafka;
    private final GenericContainer<?> pspSimulator;
    private final GenericContainer<?> notificationWorker;
    private final GenericContainer<?> ledgerguardApi;

    private final String runtimeDbPassword;
    private final String runtimeJwtSecret;
    private final String runtimeWebhookSecret;

    private final E2EDatabaseProbe databaseProbe;
    private final E2EHttpClient httpClient;

    private E2EEnvironment() {
        this.runtimeDbPassword = generateRandomSecret(24);
        this.runtimeJwtSecret = generateRandomSecret(32);
        this.runtimeWebhookSecret = generateRandomSecret(32);

        this.network = Network.newNetwork();

        log.info("Starting E2E PostgreSQL Testcontainer...");
        this.postgres = new PostgreSQLContainer<>("postgres:17.11-alpine")
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withDatabaseName("ledgerguard")
                .withUsername("postgres")
                .withPassword(runtimeDbPassword)
                .withCommand("postgres", "-c", "max_connections=300");
        this.postgres.start();

        // Initialize auxiliary databases
        this.databaseProbe = new E2EDatabaseProbe(
                postgres.getHost(),
                postgres.getMappedPort(5432),
                "postgres",
                runtimeDbPassword
        );
        this.databaseProbe.initializeDatabases("psp_simulator", "notification_worker");

        log.info("Starting E2E Kafka Testcontainer...");
        this.kafka = new KafkaContainer("apache/kafka:4.3.1")
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withListener("kafka:19092")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        this.kafka.start();

        log.info("Starting E2E PSP Simulator container...");
        this.pspSimulator = new ApplicationContainer(JarResolver.resolvePspJar())
                .withNetwork(network)
                .withNetworkAliases("psp-simulator")
                .withExposedPorts(8081)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/psp_simulator")
                .withEnv("SPRING_DATASOURCE_USERNAME", "postgres")
                .withEnv("SPRING_DATASOURCE_PASSWORD", runtimeDbPassword)
                .withEnv("POSTGRES_HOST", "postgres")
                .withEnv("POSTGRES_PORT", "5432")
                .withEnv("PSP_DB_NAME", "psp_simulator")
                .withEnv("PSP_DB_USER", "postgres")
                .withEnv("PSP_DB_PASSWORD", runtimeDbPassword)
                .withEnv("PSP_WEBHOOK_SECRET", runtimeWebhookSecret)
                .waitingFor(Wait.forLogMessage(".*Started PspSimulatorApplication.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container.psp-simulator")));
        this.pspSimulator.start();

        log.info("Starting E2E LedgerGuard API container...");
        this.ledgerguardApi = new ApplicationContainer(JarResolver.resolveApiJar())
                .withNetwork(network)
                .withNetworkAliases("ledgerguard-api")
                .withExposedPorts(8080)
                .withEnv("LEDGERGUARD_DB_URL", "jdbc:postgresql://postgres:5432/ledgerguard")
                .withEnv("LEDGERGUARD_DB_USER", "postgres")
                .withEnv("LEDGERGUARD_DB_PASSWORD", runtimeDbPassword)
                .withEnv("LEDGERGUARD_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("PSP_BASE_URL", "http://psp-simulator:8081")
                .withEnv("PSP_WEBHOOK_URL", "http://ledgerguard-api:8080/api/provider/webhooks")
                .withEnv("LEDGERGUARD_PSP_WEBHOOK_URL", "http://ledgerguard-api:8080/api/provider/webhooks")
                .withEnv("PSP_WEBHOOK_SECRET", runtimeWebhookSecret)
                .withEnv("LEDGERGUARD_JWT_SECRET", runtimeJwtSecret)
                .withEnv("LEDGERGUARD_RATE_LIMIT_ENABLED", "false")
                .withEnv("LEDGERGUARD_OUTBOX_PUBLISHER_FIXED_DELAY_MS", "200")
                .withEnv("LEDGERGUARD_PSP_POLLING_INTERVAL_MS", "1000")
                .withEnv("LEDGERGUARD_RESILIENCE_RETRY_CREATE_MAX_ATTEMPTS", "1")
                .withEnv("MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED", "false")
                .withEnv("SERVER_PORT", "8080")
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container.ledgerguard-api")));
        this.ledgerguardApi.start();
        this.databaseProbe.seedSystemAccountsIfAbsent();

        log.info("Verifying API-provisioned domain events topic before starting Notification Worker...");
        verifyDomainEventsTopicProvisioned("ledgerguard.domain-events.v1", 3, 1, Duration.ofSeconds(30));

        log.info("Starting E2E Notification Worker container...");
        this.notificationWorker = new ApplicationContainer(JarResolver.resolveNotificationWorkerJar())
                .withNetwork(network)
                .withNetworkAliases("notification-worker")
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/notification_worker")
                .withEnv("SPRING_DATASOURCE_USERNAME", "postgres")
                .withEnv("SPRING_DATASOURCE_PASSWORD", runtimeDbPassword)
                .withEnv("POSTGRES_HOST", "postgres")
                .withEnv("POSTGRES_PORT", "5432")
                .withEnv("NOTIFICATION_DB_NAME", "notification_worker")
                .withEnv("NOTIFICATION_DB_USER", "postgres")
                .withEnv("NOTIFICATION_DB_PASSWORD", runtimeDbPassword)
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .waitingFor(Wait.forLogMessage(".*Started NotificationWorkerApplication.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container.notification-worker")));
        this.notificationWorker.start();

        String apiBaseUrl = "http://" + ledgerguardApi.getHost() + ":" + ledgerguardApi.getMappedPort(8080);
        String pspBaseUrl = "http://" + pspSimulator.getHost() + ":" + pspSimulator.getMappedPort(8081);
        this.httpClient = new E2EHttpClient(apiBaseUrl, pspBaseUrl);

        log.info("E2E Environment started successfully! API: {}, PSP: {}", apiBaseUrl, pspBaseUrl);
    }

    public static synchronized E2EEnvironment getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new E2EEnvironment();
            STARTED.set(true);
            Runtime.getRuntime().addShutdownHook(new Thread(E2EEnvironment::shutdown));
        }
        return INSTANCE;
    }

    public static synchronized void shutdown() {
        if (STARTED.compareAndSet(true, false) && INSTANCE != null) {
            log.info("Stopping E2E containers and network...");
            try {
                INSTANCE.ledgerguardApi.stop();
            } catch (Exception ignored) {}
            try {
                INSTANCE.notificationWorker.stop();
            } catch (Exception ignored) {}
            try {
                INSTANCE.pspSimulator.stop();
            } catch (Exception ignored) {}
            try {
                INSTANCE.kafka.stop();
            } catch (Exception ignored) {}
            try {
                INSTANCE.postgres.stop();
            } catch (Exception ignored) {}
            try {
                INSTANCE.network.close();
            } catch (Exception ignored) {}
            INSTANCE = null;
            log.info("E2E Environment shutdown complete.");
        }
    }

    public E2EDatabaseProbe getDatabaseProbe() {
        return databaseProbe;
    }

    public E2EHttpClient getHttpClient() {
        return httpClient;
    }

    public String getRuntimeWebhookSecret() {
        return runtimeWebhookSecret;
    }

    private void verifyDomainEventsTopicProvisioned(String topicName, int expectedPartitions, int expectedReplicationFactor, Duration timeout) {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000");

        Instant deadline = Instant.now().plus(timeout);
        Exception lastException = null;
        AdminClient adminClient = AdminClient.create(adminProps);
        try {
            while (Instant.now().isBefore(deadline)) {
                try {
                    Map<String, TopicDescription> topics = adminClient.describeTopics(Collections.singletonList(topicName))
                            .allTopicNames()
                            .get(3, TimeUnit.SECONDS);

                    TopicDescription desc = topics.get(topicName);
                    if (desc != null && desc.partitions().size() == expectedPartitions) {
                        boolean allReplicasMatch = desc.partitions().stream()
                                .allMatch(p -> p.replicas().size() == expectedReplicationFactor);
                        if (allReplicasMatch) {
                            log.info("Verified topic '{}' is provisioned with {} partitions and replication factor {}",
                                    topicName, expectedPartitions, expectedReplicationFactor);
                            return;
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for topic '" + topicName + "' provisioning", ie);
                } catch (Exception e) {
                    lastException = e;
                    log.debug("Topic '{}' not yet fully provisioned, waiting... ({})", topicName, e.getMessage());
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for topic '" + topicName + "' provisioning", ie);
                }
            }
        } finally {
            adminClient.close(Duration.ofSeconds(5));
        }

        String message = String.format(
                "Timed out after %s waiting for topic '%s' to be provisioned with %d partitions and replication factor %d",
                timeout, topicName, expectedPartitions, expectedReplicationFactor
        );
        if (lastException != null) {
            throw new IllegalStateException(message, lastException);
        } else {
            throw new IllegalStateException(message);
        }
    }

    private static String generateRandomSecret(int numBytes) {
        byte[] bytes = new byte[numBytes];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
