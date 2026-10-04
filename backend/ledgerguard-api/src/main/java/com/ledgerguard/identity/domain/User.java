package com.ledgerguard.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "full_name", length = 120)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private UserStatus status;

    @Column(name = "credential_version", nullable = false)
    private int credentialVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // Required by JPA
    }

    public User(UUID id, String fullName, String email, String passwordHash, UserRole role, UserStatus status, int credentialVersion) {
        this.id = id != null ? id : UUID.randomUUID();
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status != null ? status : UserStatus.ACTIVE;
        this.credentialVersion = credentialVersion > 0 ? credentialVersion : 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public User(UUID id, String fullName, String email, String passwordHash, UserRole role, UserStatus status) {
        this(id, fullName, email, passwordHash, role, status, 1);
    }

    public User(UUID id, String email, String passwordHash, UserRole role, UserStatus status) {
        this(id, null, email, passwordHash, role, status, 1);
    }

    public static User create(String fullName, String email, String passwordHash, UserRole role) {
        return new User(UUID.randomUUID(), fullName, email, passwordHash, role, UserStatus.ACTIVE);
    }

    public static User create(String email, String passwordHash, UserRole role) {
        return create(null, email, passwordHash, role);
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }

    public int getCredentialVersion() {
        return credentialVersion;
    }

    public void incrementCredentialVersion() {
        if (this.credentialVersion >= Integer.MAX_VALUE) {
            throw new IllegalStateException("Credential version overflow: maximum credential updates reached for user.");
        }
        this.credentialVersion++;
    }

    public void updatePassword(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "passwordHash cannot be null");
        incrementCredentialVersion();
    }

    public void updateFullName(String newFullName) {
        this.fullName = newFullName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
