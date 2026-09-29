package com.ledgerguard.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.api.dto.LoginRequest;
import com.ledgerguard.identity.api.dto.RegisterRequest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.identity.infrastructure.ProductionOpsProvisioningJob;
import com.ledgerguard.identity.infrastructure.ProductionOpsProvisioningProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Production OPS Identity Provisioning & Authentication End-to-End Integration Test")
class ProductionOpsAuthenticationIntegrationTest extends AbstractIntegrationTest {

    private static final String PROVISIONED_EMAIL = "auth.ops.verify@ledgerguard.example.com";
    private static final String PROVISIONED_PASSWORD = "VerifiedOpsPassword123!";
    private static final String PROVISIONED_FULL_NAME = "Verified Operations Officer";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Executes OPS provisioning against PostgreSQL, proves credentials authenticate, verifies role, and confirms public registration rejects OPS")
    void provisionedOpsAccountAuthenticatesSuccessfully() throws Exception {
        // 1. Execute production OPS provisioning against PostgreSQL using non-web context
        ProductionOpsProvisioningProperties properties = new ProductionOpsProvisioningProperties();
        properties.setEnabled(true);
        properties.setEmail(PROVISIONED_EMAIL);
        properties.setPassword(PROVISIONED_PASSWORD);
        properties.setFullName(PROVISIONED_FULL_NAME);
        properties.setExitOnCompletion(false);

        ApplicationContext nonWebContext = mock(ApplicationContext.class);
        Environment nonWebEnv = mock(Environment.class);
        when(nonWebContext.getEnvironment()).thenReturn(nonWebEnv);
        when(nonWebEnv.getProperty("spring.main.web-application-type")).thenReturn("none");

        ProductionOpsProvisioningJob provisioningJob = new ProductionOpsProvisioningJob(
                properties, userRepository, passwordEncoder, nonWebContext
        );
        provisioningJob.run(new DefaultApplicationArguments());

        // 2. Confirms exactly one OPS user exists
        Integer opsUserCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ? AND role = 'OPS'",
                Integer.class,
                PROVISIONED_EMAIL
        );
        assertThat(opsUserCount).isEqualTo(1);

        User provisionedUser = userRepository.findByEmail(PROVISIONED_EMAIL).orElseThrow();
        UUID userId = provisionedUser.getId();
        assertThat(provisionedUser.getRole()).isEqualTo(UserRole.OPS);
        assertThat(provisionedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(provisionedUser.getFullName()).isEqualTo(PROVISIONED_FULL_NAME);

        // 3. Confirms no wallet or ledger account exists for it
        Integer ledgerAccountCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_accounts WHERE owner_user_id = ?",
                Integer.class,
                userId
        );
        assertThat(ledgerAccountCount).isZero();

        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_balance_snapshots WHERE ledger_account_id IN (SELECT id FROM ledger_accounts WHERE owner_user_id = ?)",
                Integer.class,
                userId
        );
        assertThat(snapshotCount).isZero();

        // 4. Confirms plaintext password is not stored
        String storedPasswordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class,
                userId
        );
        assertThat(storedPasswordHash).startsWith("$2a$");
        assertThat(storedPasswordHash).doesNotContain(PROVISIONED_PASSWORD);
        assertThat(passwordEncoder.matches(PROVISIONED_PASSWORD, storedPasswordHash)).isTrue();

        // 5. Calls POST /api/auth/login using the provisioned email/password
        LoginRequest loginRequest = new LoginRequest(PROVISIONED_EMAIL, PROVISIONED_PASSWORD);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(PROVISIONED_EMAIL))
                .andExpect(jsonPath("$.user.role").value("OPS"))
                .andReturn();

        // 6. Receives an access token (token and password are never printed/logged)
        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();
        assertThat(accessToken).isNotNull().isNotBlank();

        // 7. Verifies /api/auth/me returns role OPS
        mockMvc.perform(get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(PROVISIONED_EMAIL))
                .andExpect(jsonPath("$.role").value("OPS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.fullName").value(PROVISIONED_FULL_NAME));

        // 8. Verifies /api/reconciliation/summary returns 200
        mockMvc.perform(get("/api/reconciliation/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").isNumber());

        // 9. Verifies public registration still rejects OPS
        RegisterRequest publicOpsRegistration = new RegisterRequest(
                "Infiltration Attempt",
                "malicious.ops." + UUID.randomUUID() + "@ledgerguard.example.com",
                "MaliciousPass123456!",
                UserRole.OPS
        );
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(publicOpsRegistration)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Registration with OPS role is not permitted."))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Exit-on-completion lifecycle commits transaction before process exit and returns exit code 0")
    void exitOnCompletionLifecycleCommitsBeforeExit() throws Exception {
        String processEmail = "process.exit.ops@ledgerguard.example.com";
        String processPassword = "ProcessExitPassword456!";
        String processFullName = "Process Exit Officer";

        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                "com.ledgerguard.LedgerGuardApplication"
        );
        java.util.Map<String, String> env = pb.environment();
        env.put("SPRING_PROFILES_ACTIVE", "test,ops-provision");
        env.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "none");
        env.put("LEDGERGUARD_OPS_PROVISION_ENABLED", "true");
        env.put("LEDGERGUARD_OPS_PROVISION_EMAIL", processEmail);
        env.put("LEDGERGUARD_OPS_PROVISION_PASSWORD", processPassword);
        env.put("LEDGERGUARD_OPS_PROVISION_FULL_NAME", processFullName);
        env.put("LEDGERGUARD_OPS_PROVISION_EXIT_ON_COMPLETION", "true");
        env.put("LEDGERGUARD_DB_URL", POSTGRES_CONTAINER.getJdbcUrl());
        env.put("LEDGERGUARD_DB_USER", POSTGRES_CONTAINER.getUsername());
        env.put("LEDGERGUARD_DB_PASSWORD", POSTGRES_CONTAINER.getPassword());
        env.put("LEDGERGUARD_KAFKA_BOOTSTRAP_SERVERS", KAFKA_CONTAINER.getBootstrapServers());
        env.put("LEDGERGUARD_JWT_SECRET", RUNTIME_JWT_SECRET);
        env.put("PSP_WEBHOOK_SECRET", RUNTIME_WEBHOOK_SECRET);
        env.put("LEDGERGUARD_PSP_POLLING_ENABLED", "false");

        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder processOutput = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processOutput.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(finished).as("Separate JVM subprocess should terminate within 60 seconds").isTrue();
        int exitCode = process.exitValue();
        String processLogs = processOutput.toString();
        String sanitizedLogs = processLogs
                .replace(processPassword, "[REDACTED_PASSWORD]")
                .replace(POSTGRES_CONTAINER.getPassword(), "[REDACTED_DB_PASS]")
                .replace(RUNTIME_JWT_SECRET, "[REDACTED_JWT_SECRET]")
                .replace(RUNTIME_WEBHOOK_SECRET, "[REDACTED_WEBHOOK_SECRET]");

        assertThat(exitCode)
                .withFailMessage("Subprocess failed with exit code %d.\nCommand: %s\nWorking Dir: %s\nLogs:\n%s",
                        exitCode, pb.command(), pb.directory(), sanitizedLogs)
                .isZero();

        assertThat(processLogs).contains("[OPS PROVISION] Successfully provisioned production OPS user");
        assertThat(processLogs).contains("[OPS PROVISION] Production OPS provisioning committed successfully (outcome=CREATED)");
        assertThat(processLogs).contains("[OPS PROVISION] Production OPS provisioning finished. Exiting process with code 0.");
        assertThat(processLogs).doesNotContain(processPassword);

        // Reconnect via independent JDBC connection
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                POSTGRES_CONTAINER.getJdbcUrl(),
                POSTGRES_CONTAINER.getUsername(),
                POSTGRES_CONTAINER.getPassword())) {

            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, password_hash, role, status, full_name FROM users WHERE email = ?")) {
                stmt.setString(1, processEmail);
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    UUID opsId = UUID.fromString(rs.getString("id"));
                    String hash = rs.getString("password_hash");
                    assertThat(rs.getString("role")).isEqualTo("OPS");
                    assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                    assertThat(rs.getString("full_name")).isEqualTo(processFullName);
                    assertThat(hash).startsWith("$2a$");
                    assertThat(hash).doesNotContain(processPassword);
                    assertThat(passwordEncoder.matches(processPassword, hash)).isTrue();

                    // Confirm 0 ledger accounts and 0 snapshots
                    try (java.sql.PreparedStatement acctStmt = conn.prepareStatement(
                            "SELECT count(*) FROM ledger_accounts WHERE owner_user_id = ?")) {
                        acctStmt.setObject(1, opsId);
                        try (java.sql.ResultSet acctRs = acctStmt.executeQuery()) {
                            acctRs.next();
                            assertThat(acctRs.getInt(1)).isZero();
                        }
                    }

                    try (java.sql.PreparedStatement snapStmt = conn.prepareStatement(
                            "SELECT count(*) FROM ledger_balance_snapshots WHERE ledger_account_id IN (SELECT id FROM ledger_accounts WHERE owner_user_id = ?)")) {
                        snapStmt.setObject(1, opsId);
                        try (java.sql.ResultSet snapRs = snapStmt.executeQuery()) {
                            snapRs.next();
                            assertThat(snapRs.getInt(1)).isZero();
                        }
                    }
                }
            }
        }

        // Verify the provisioned credentials authenticate via the normal running API
        LoginRequest loginRequest = new LoginRequest(processEmail, processPassword);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(processEmail))
                .andExpect(jsonPath("$.user.role").value("OPS"))
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPS"))
                .andExpect(jsonPath("$.email").value(processEmail));
    }
}
