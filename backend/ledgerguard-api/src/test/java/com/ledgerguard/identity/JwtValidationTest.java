package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.shared.security.JwtProperties;
import com.ledgerguard.shared.security.JwtTokenService;
import com.ledgerguard.shared.security.SecurityConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidationTest extends AbstractIntegrationTest {

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private com.ledgerguard.identity.domain.UserRepository userRepository;

    @Test
    @DisplayName("Generated JWT contains subject, role, issuer, jti and valid expiration without sensitive claims")
    void jwtContainsRequiredClaimsAndValidSignature() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "jwt.test@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String token = jwtTokenService.generateAccessToken(user);
        assertThat(token).isNotBlank();

        Jwt decodedJwt = jwtDecoder.decode(token);

        assertThat(decodedJwt.getSubject()).isEqualTo(userId.toString());
        assertThat(decodedJwt.getClaimAsString("iss")).isEqualTo("ledgerguard");
        assertThat(decodedJwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(decodedJwt.getId()).isNotBlank();
        assertThat(decodedJwt.getExpiresAt()).isNotNull();
        assertThat(decodedJwt.getIssuedAt()).isNotNull();
        assertThat(decodedJwt.getHeaders().get("alg")).isEqualTo("HS256");

        assertThat(decodedJwt.getClaims()).doesNotContainKeys("password", "passwordHash", "refreshToken");
    }

    @Test
    @DisplayName("JWT signed with the correct key and HS256 but with wrong issuer is rejected by JwtDecoder")
    void tokenSignedWithCorrectKeyButWrongIssuerIsRejected() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("unauthorized-fake-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .subject(UUID.randomUUID().toString())
                .claim("role", "CUSTOMER")
                .id(UUID.randomUUID().toString())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String wrongIssuerToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        assertThatThrownBy(() -> jwtDecoder.decode(wrongIssuerToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    @Test
    @DisplayName("JWT signed with a different key is rejected with BadJwtException")
    void tokenSignedWithWrongKeyIsRejected() {
        byte[] wrongKeyBytes = new byte[32];
        new SecureRandom().nextBytes(wrongKeyBytes);
        SecretKeySpec wrongKey = new SecretKeySpec(wrongKeyBytes, "HmacSHA256");
        NimbusJwtEncoder wrongEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(wrongKey));

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ledgerguard")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .subject(UUID.randomUUID().toString())
                .claim("role", "CUSTOMER")
                .id(UUID.randomUUID().toString())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String tamperedToken = wrongEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        assertThatThrownBy(() -> jwtDecoder.decode(tamperedToken))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    @DisplayName("Configuration rejects undersized JWT signing secret (< 32 bytes)")
    void undersizedSecretThrowsClearException() {
        JwtProperties props = new JwtProperties();
        props.setSecret("short-secret-less-than-32-bytes");

        SecurityConfig config = new SecurityConfig(props, null, null);
        assertThatThrownBy(config::jwtSecretKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be at least 256 bits (32 bytes)");
    }

    @Test
    @DisplayName("JWT decoder rejects missing, fractional, non-positive, overflowing, or string-malformed cv claims")
    void rejectsMalformedCredentialVersionClaims() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "cv.malformed@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        Object[] invalidCvClaims = new Object[]{
                null,                   // missing cv
                1.5,                    // fractional number
                0,                      // zero
                -1,                     // negative
                3000000000L,            // overflowing int
                "1.5",                  // fractional string
                "0",                    // zero string
                "-5",                   // negative string
                "99999999999999",       // overflowing string
                "not-a-number"          // malformed string
        };

        for (Object invalidCv : invalidCvClaims) {
            Instant now = Instant.now();
            JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                    .issuer("ledgerguard")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(900))
                    .subject(userId.toString())
                    .claim("role", "CUSTOMER")
                    .id(UUID.randomUUID().toString());
            if (invalidCv != null) {
                builder.claim("cv", invalidCv);
            }
            JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
            String token = jwtEncoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();

            assertThatThrownBy(() -> jwtDecoder.decode(token))
                    .isInstanceOf(JwtValidationException.class);
        }
    }
}
