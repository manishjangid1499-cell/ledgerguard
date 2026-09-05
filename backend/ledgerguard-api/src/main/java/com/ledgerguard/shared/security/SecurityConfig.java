package com.ledgerguard.shared.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.ledgerguard.shared.ratelimit.RateLimitFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final int MIN_KEY_BYTE_LENGTH = 32;

    private final JwtProperties jwtProperties;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(JwtProperties jwtProperties,
                          CustomAuthenticationEntryPoint authenticationEntryPoint,
                          CustomAccessDeniedHandler accessDeniedHandler) {
        this(jwtProperties, authenticationEntryPoint, accessDeniedHandler, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SecurityConfig(JwtProperties jwtProperties,
                          CustomAuthenticationEntryPoint authenticationEntryPoint,
                          CustomAccessDeniedHandler accessDeniedHandler,
                          RateLimitFilter rateLimitFilter) {
        this.jwtProperties = jwtProperties;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${ledgerguard.security.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = (allowedOrigins != null)
                ? allowedOrigins.stream().filter(s -> s != null && !s.isBlank()).toList()
                : Collections.emptyList();

        if (!origins.isEmpty()) {
            configuration.setAllowedOrigins(origins);
            configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
            configuration.setExposedHeaders(List.of("Retry-After"));
            configuration.setAllowCredentials(true);
            configuration.setMaxAge(3600L);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecretKey jwtSecretKey() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT signing secret (ledgerguard.security.jwt.secret) must be configured.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_BYTE_LENGTH) {
            throw new IllegalStateException("JWT signing secret must be at least 256 bits (32 bytes). Current length: " + keyBytes.length + " bytes.");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.getIssuer()));
        return jwtDecoder;
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            String role = jwt.getClaimAsString("role");
            Collection<GrantedAuthority> authorities;
            if (role != null && !role.isBlank()) {
                authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            } else {
                authorities = Collections.emptyList();
            }
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000L)
                                .includeSubDomains(true)
                        )
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Public authentication endpoints
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // Public provider webhook ingress (authenticated via HMAC)
                        .requestMatchers(HttpMethod.POST, "/api/provider/webhooks").permitAll()
                        // Public actuator health/info endpoints
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Transfers endpoints
                        .requestMatchers(HttpMethod.POST, "/api/transfers").hasAnyRole("CUSTOMER", "MERCHANT")
                        .requestMatchers(HttpMethod.GET, "/api/transfers").hasAnyRole("CUSTOMER", "MERCHANT")
                        .requestMatchers(HttpMethod.GET, "/api/transfers/**").hasAnyRole("CUSTOMER", "MERCHANT")
                        // Wallet read endpoint
                        .requestMatchers(HttpMethod.GET, "/api/wallets/me").hasAnyRole("CUSTOMER", "MERCHANT")
                        // Refunds endpoint
                        .requestMatchers(HttpMethod.POST, "/api/payments/*/refund").hasRole("MERCHANT")
                        // Payments endpoint
                        .requestMatchers(HttpMethod.POST, "/api/payments").hasRole("CUSTOMER")
                        // External Funding endpoint (Phase 27 authorization alignment)
                        .requestMatchers(HttpMethod.POST, "/api/funding").hasRole("CUSTOMER")
                        // External Payouts endpoint (Phase 27 authorization alignment)
                        .requestMatchers(HttpMethod.POST, "/api/payouts").hasAnyRole("CUSTOMER", "MERCHANT")
                        // Operations and reconciliation routes
                        .requestMatchers("/api/ops/**", "/api/reconciliation/**").hasRole("OPS")
                        // Protected API endpoints
                        .requestMatchers("/api/**").authenticated()
                        // Any other request
                        .anyRequest().permitAll()
                );

        if (rateLimitFilter != null) {
            http.addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
        }

        return http.build();
    }
}
