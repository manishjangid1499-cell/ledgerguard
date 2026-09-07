package com.ledgerguard.e2e;

import com.ledgerguard.e2e.infrastructure.E2EDatabaseProbe;
import com.ledgerguard.e2e.infrastructure.E2EEnvironment;
import com.ledgerguard.e2e.infrastructure.E2EHttpClient;
import com.ledgerguard.e2e.infrastructure.TestIdentityFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractE2ETest {

    protected E2EEnvironment env;
    protected E2EHttpClient http;
    protected E2EDatabaseProbe db;
    protected E2EHttpClient httpClient;
    protected E2EDatabaseProbe dbProbe;
    protected TestIdentityFactory idFactory;

    @BeforeAll
    void setupSuite() {
        this.env = E2EEnvironment.getInstance();
        this.http = env.getHttpClient();
        this.db = env.getDatabaseProbe();
        this.httpClient = this.http;
        this.dbProbe = this.db;
        this.idFactory = new TestIdentityFactory();
    }
}
