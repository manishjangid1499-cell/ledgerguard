package com.ledgerguard.identity.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Query("SELECT p.user.id FROM PasswordResetToken p WHERE p.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PasswordResetToken p JOIN FETCH p.user WHERE p.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    @Query("SELECT p FROM PasswordResetToken p JOIN FETCH p.user WHERE p.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashWithUser(@Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PasswordResetToken p SET p.consumedAt = :now WHERE p.user.id = :userId AND p.consumedAt IS NULL")
    int invalidateAllActiveForUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PasswordResetToken p WHERE p.expiresAt < :cutoff OR p.consumedAt IS NOT NULL")
    int deleteExpiredOrConsumed(@Param("cutoff") Instant cutoff);
}
