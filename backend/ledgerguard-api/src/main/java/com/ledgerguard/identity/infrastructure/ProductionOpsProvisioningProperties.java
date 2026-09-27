package com.ledgerguard.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for explicit, one-time production OPS account provisioning.
 * <p>
 * Strictly disabled by default during normal application startup.
 */
@Component
@ConfigurationProperties(prefix = "ledgerguard.ops.provision")
public class ProductionOpsProvisioningProperties {

    private boolean enabled = false;
    private String email = "";
    private String password = "";
    private String fullName = "Operations Administrator";
    private boolean exitOnCompletion = true;
    private boolean rotatePassword = false;

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

    public boolean isExitOnCompletion() {
        return exitOnCompletion;
    }

    public void setExitOnCompletion(boolean exitOnCompletion) {
        this.exitOnCompletion = exitOnCompletion;
    }

    public boolean isRotatePassword() {
        return rotatePassword;
    }

    public void setRotatePassword(boolean rotatePassword) {
        this.rotatePassword = rotatePassword;
    }

    public boolean isAllowPasswordRotation() {
        return rotatePassword;
    }

    public void setAllowPasswordRotation(boolean allowPasswordRotation) {
        this.rotatePassword = allowPasswordRotation;
    }
}
