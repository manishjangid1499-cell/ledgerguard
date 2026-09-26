package com.ledgerguard.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for development-only OPS account provisioning.
 * <p>
 * Strictly disabled by default. Never enabled in production environments.
 */
@Component
@ConfigurationProperties(prefix = "ledgerguard.bootstrap.ops")
public class OpsBootstrapProperties {

    private boolean enabled = false;
    private String email = "";
    private String password = "";
    private String fullName = "Operations Engineer";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
