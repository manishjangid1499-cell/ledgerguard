package com.ledgerguard.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * Explicit, one-time production CLI task that provisions an initial OPS account
 * from secure deployment environment secrets.
 * <p>
 * Key Invariants:
 * <ul>
 *   <li>Requires dedicated profile: {@code ops-provision}.</li>
 *   <li>Disabled by default; activated strictly when {@code ledgerguard.ops.provision.enabled=true}.</li>
 *   <li>Requires non-web execution with {@code spring.main.web-application-type=none}.</li>
 *   <li>Fails fast if executed within a servlet or reactive web context.</li>
 *   <li>Not an HTTP endpoint; executed exclusively via CLI or one-time container run.</li>
 *   <li>Reads credentials exclusively from deployment properties/secrets.</li>
 *   <li>BCrypt hash only; never logs credentials, tokens, or hashes.</li>
 *   <li>Refuses role promotion if target email already belongs to a CUSTOMER or MERCHANT.</li>
 *   <li>Idempotent for an existing matching active OPS account.</li>
 *   <li>Strictly never provisions a wallet or ledger account (OPS accounts have no wallets).</li>
 *   <li>Commits database transaction before initiating context exit.</li>
 *   <li>Exits the process with code 0 upon confirmed success; non-zero exit upon failure.</li>
 * </ul>
 */
@Component
@Profile("ops-provision")
@ConditionalOnProperty(prefix = "ledgerguard.ops.provision", name = "enabled", havingValue = "true")
public class ProductionOpsProvisioningJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionOpsProvisioningJob.class);

    private final ProductionOpsProvisioningProperties properties;
    private final ProductionOpsProvisioningService provisioningService;
    private final ApplicationContext applicationContext;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionOpsProvisioningJob(ProductionOpsProvisioningProperties properties,
                                        ProductionOpsProvisioningService provisioningService,
                                        ApplicationContext applicationContext) {
        this.properties = properties;
        this.provisioningService = provisioningService;
        this.applicationContext = applicationContext;
    }

    public ProductionOpsProvisioningJob(ProductionOpsProvisioningProperties properties,
                                        com.ledgerguard.identity.domain.UserRepository userRepository,
                                        org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                                        ApplicationContext applicationContext) {
        this(properties, new ProductionOpsProvisioningService(userRepository, passwordEncoder), applicationContext);
    }

    @Override
    public void run(ApplicationArguments args) {
        validateNonWebExecution();
        try {
            ProductionOpsProvisioningService.ProvisioningResult result = provisioningService.provision(properties);
            log.info("[OPS PROVISION] Production OPS provisioning committed successfully (outcome={}).", result.outcome());
            if (properties.isExitOnCompletion()) {
                log.info("[OPS PROVISION] Production OPS provisioning finished. Exiting process with code 0.");
                SpringApplication.exit(applicationContext, () -> 0);
            }
        } catch (Exception e) {
            log.error("[OPS PROVISION FAILED] Production OPS provisioning failed: {}", e.getMessage(), e);
            if (properties.isExitOnCompletion()) {
                SpringApplication.exit(applicationContext, () -> 1);
            }
            throw e;
        }
    }

    public void validateNonWebExecution() {
        if (applicationContext instanceof WebApplicationContext
                || applicationContext.getClass().getName().toLowerCase().contains("web")
                || applicationContext.getClass().getName().toLowerCase().contains("reactive")) {
            throw new IllegalStateException("Production OPS provisioning refused: cannot execute in web-server mode. "
                    + "Provisioning must run as a non-web ephemeral CLI task with spring.main.web-application-type=none.");
        }
        String webAppType = applicationContext.getEnvironment().getProperty("spring.main.web-application-type");
        if (webAppType != null && !"none".equalsIgnoreCase(webAppType.trim())) {
            throw new IllegalStateException("Production OPS provisioning refused: spring.main.web-application-type must be 'none'. "
                    + "Current value: " + webAppType);
        }
    }

    public void provisionProductionOpsUser() {
        provisioningService.provision(properties);
    }
}
