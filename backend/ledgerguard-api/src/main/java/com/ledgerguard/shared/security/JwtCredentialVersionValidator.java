package com.ledgerguard.shared.security;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserStatus;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates that an access JWT has a valid credential version ('cv') matching
 * the user's current database state and that the user account is ACTIVE.
 * Any missing or mismatched 'cv' claim or non-ACTIVE status invalidates the token.
 */
public class JwtCredentialVersionValidator implements OAuth2TokenValidator<Jwt> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtCredentialVersionValidator.class);
    private final UserRepository userRepository;

    public JwtCredentialVersionValidator(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Subject is missing", null));
        }

        UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Subject is not a valid UUID", null));
        }

        Object cvClaim = jwt.getClaim("cv");
        if (cvClaim == null) {
            log.warn("[JWT CV] Credential version claim (cv) is missing for subject={}", subject);
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credential version claim (cv) is missing", null));
        }

        int tokenCv;
        if (cvClaim instanceof Number number) {
            double d = number.doubleValue();
            long l = number.longValue();
            if (d != (double) l) {
                log.warn("[JWT CV] Fractional cv claim rejected: {}", cvClaim);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credential version claim (cv) must be integral", null));
            }
            if (l <= 0 || l > Integer.MAX_VALUE) {
                log.warn("[JWT CV] Out-of-range cv claim rejected: {}", cvClaim);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credential version claim (cv) out of range", null));
            }
            tokenCv = (int) l;
        } else if (cvClaim instanceof String s) {
            if (!s.matches("^[1-9][0-9]{0,9}$")) {
                log.warn("[JWT CV] Malformed cv claim rejected: {}", s);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credential version claim (cv) is malformed", null));
            }
            try {
                long l = Long.parseLong(s);
                if (l <= 0 || l > Integer.MAX_VALUE) {
                    log.warn("[JWT CV] Out-of-range cv claim rejected: {}", s);
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credential version claim (cv) out of range", null));
                }
                tokenCv = (int) l;
            } catch (NumberFormatException e) {
                log.warn("[JWT CV] Overflowing cv claim rejected: {}", s);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credential version claim (cv) is malformed", null));
            }
        } else {
            log.warn("[JWT CV] Unsupported cv claim type: {}", cvClaim.getClass().getName());
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credential version claim (cv) is invalid", null));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.warn("[JWT CV] User not found for subject={}", userId);
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "User not found", null));
        }

        User user = userOpt.get();
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("[JWT CV] User is not ACTIVE: userId={}, status={}", userId, user.getStatus());
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "User account is not active", null));
        }

        if (user.getCredentialVersion() != tokenCv) {
            log.info("[JWT CV] Version mismatch: userId={}, tokenCv={}, dbCv={}", userId, tokenCv, user.getCredentialVersion());
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token credential version is invalid or revoked", null));
        }

        return OAuth2TokenValidatorResult.success();
    }
}
