package com.ledgerguard.idempotency.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SHA-256 deterministic request fingerprint represented as a 64-character lowercase hexadecimal string.
 */
public final class RequestFingerprint {

    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final String value;

    private RequestFingerprint(String value) {
        this.value = value;
    }

    /**
     * Creates a RequestFingerprint from an existing 64-character lowercase hex string.
     *
     * @param value 64-character hex string
     * @return validated RequestFingerprint
     */
    public static RequestFingerprint fromHex(String value) {
        Objects.requireNonNull(value, "Request fingerprint must not be null");
        if (!HEX_64_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Request fingerprint must be exactly 64 lowercase hexadecimal characters");
        }
        return new RequestFingerprint(value);
    }

    /**
     * Computes a SHA-256 RequestFingerprint from canonical raw bytes.
     *
     * @param canonicalBytes raw canonical input bytes
     * @return computed RequestFingerprint
     */
    public static RequestFingerprint of(byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "Canonical bytes must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalBytes);
            String hex = HexFormat.of().formatHex(hash);
            return new RequestFingerprint(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest algorithm not available", e);
        }
    }

    /**
     * Computes a SHA-256 RequestFingerprint from a canonical string using UTF-8 encoding.
     *
     * @param canonicalString canonical string representation
     * @return computed RequestFingerprint
     */
    public static RequestFingerprint of(String canonicalString) {
        Objects.requireNonNull(canonicalString, "Canonical string must not be null");
        return of(canonicalString.getBytes(StandardCharsets.UTF_8));
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestFingerprint that = (RequestFingerprint) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
