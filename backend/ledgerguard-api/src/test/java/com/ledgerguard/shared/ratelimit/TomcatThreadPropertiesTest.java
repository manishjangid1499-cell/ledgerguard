package com.ledgerguard.shared.ratelimit;

import com.ledgerguard.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TomcatThreadPropertiesTest extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Tomcat bounded thread pool and queue properties are correctly bound")
    void tomcatPropertiesAreBound() {

        assertThat(environment.getProperty("server.tomcat.threads.max", Integer.class)).isEqualTo(50);
        assertThat(environment.getProperty("server.tomcat.threads.min-spare", Integer.class)).isEqualTo(10);
        assertThat(environment.getProperty("server.tomcat.threads.max-queue-capacity", Integer.class)).isEqualTo(50);
        assertThat(environment.getProperty("server.tomcat.accept-count", Integer.class)).isEqualTo(50);
        assertThat(environment.getProperty("server.tomcat.max-connections", Integer.class)).isEqualTo(1000);
    }

    @Test
    @DisplayName("Hikari connection pool is bounded and production application.yml configures maximum-pool-size=10")
    void hikariConnectionPoolIsBounded() throws Exception {
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean yamlFactory =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yamlFactory.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        java.util.Properties props = yamlFactory.getObject();
        assertThat(props).isNotNull();
        String rawPoolProp = props.getProperty("spring.datasource.hikari.maximum-pool-size");
        assertThat(rawPoolProp).isNotNull();
        assertThat(environment.resolvePlaceholders(rawPoolProp)).isEqualTo("10");

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getMaximumPoolSize()).isLessThanOrEqualTo(10);
    }
}
