package com.ledgerguard.notification.delivery;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies SMTP and transport exceptions into transient (retryable) vs permanent (fatal) failures.
 *
 * Traverses nested Jakarta Mail and Spring Mail exception chains (causes, suppressed,
 * MailSendException message exceptions, and MessagingException linked next-exceptions)
 * to extract structured SMTP response codes and error types.
 */
public final class SmtpErrorClassifier {

    private static final Set<Integer> KNOWN_PERMANENT_5XX_CODES = Set.of(
            500, 501, 502, 503, 504, 521, 530, 534, 535, 538, 550, 551, 552, 553, 554, 555, 556
    );

    private static final Set<Integer> KNOWN_TRANSIENT_4XX_CODES = Set.of(
            421, 450, 451, 452, 454, 455
    );

    private static final Pattern SMTP_CODE_PATTERN = Pattern.compile(
            "(?<![:\\d])([45]\\d{2})(?![\\d])"
    );

    private static final Pattern ENHANCED_STATUS_PATTERN = Pattern.compile(
            "\\b([45])\\.\\d{1,3}\\.\\d{1,3}\\b"
    );

    private SmtpErrorClassifier() {
    }

    public static SmtpFailureClassification classify(Throwable root) {
        if (root == null) {
            return SmtpFailureClassification.transientFailure(null, "UNKNOWN", "Null exception");
        }

        List<Throwable> chain = collectExceptionChain(root);

        // Pass 1: Structured Authentication Failure (535, MailAuthenticationException, AuthenticationFailedException)
        for (Throwable t : chain) {
            if (isAuthenticationFailure(t)) {
                return SmtpFailureClassification.permanent(
                        extractStructuredReturnCode(t).orElse(535),
                        "AUTHENTICATION_FAILURE",
                        "SMTP authentication failed"
                );
            }
        }

        // Pass 2: Structured Permanent 5xx Codes from Jakarta Mail SMTPSendFailedException / SMTPAddressFailedException
        for (Throwable t : chain) {
            Optional<Integer> codeOpt = extractStructuredReturnCode(t);
            if (codeOpt.isPresent()) {
                int code = codeOpt.get();
                if (code == 535) {
                    return SmtpFailureClassification.permanent(535, "AUTHENTICATION_FAILURE", "SMTP authentication failed (535)");
                }
                if (code >= 500 && code <= 599) {
                    return SmtpFailureClassification.permanent(code, "PERMANENT_SMTP_5XX", "Permanent SMTP rejection: " + code);
                }
            }
        }

        // Pass 3: Address and Recipient Rejections
        for (Throwable t : chain) {
            if (t instanceof AddressException) {
                return SmtpFailureClassification.permanent(550, "INVALID_RECIPIENT", "Invalid recipient email address syntax: " + t.getMessage());
            }
            if (t instanceof SendFailedException sfe) {
                if (sfe.getInvalidAddresses() != null && sfe.getInvalidAddresses().length > 0) {
                    return SmtpFailureClassification.permanent(550, "INVALID_RECIPIENT", "Invalid recipient address rejected by mail server");
                }
            }
        }

        // Pass 4: Structured Transient 4xx Codes from Jakarta Mail
        for (Throwable t : chain) {
            Optional<Integer> codeOpt = extractStructuredReturnCode(t);
            if (codeOpt.isPresent()) {
                int code = codeOpt.get();
                if (code >= 400 && code <= 499) {
                    return SmtpFailureClassification.transientFailure(code, "TRANSIENT_SMTP_4XX", "Temporary SMTP failure: " + code);
                }
            }
        }

        // Pass 5: Unstructured Text Inspection for SMTP Status Codes (e.g. from server response banner / message text)
        for (Throwable t : chain) {
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                // Check enhanced status code first (e.g. 5.x.x vs 4.x.x)
                Matcher enhMatcher = ENHANCED_STATUS_PATTERN.matcher(msg);
                if (enhMatcher.find()) {
                    String classDigit = enhMatcher.group(1);
                    if ("5".equals(classDigit)) {
                        return SmtpFailureClassification.permanent(550, "PERMANENT_SMTP_5XX", "Permanent SMTP failure indicated by enhanced status code: " + enhMatcher.group());
                    } else if ("4".equals(classDigit)) {
                        return SmtpFailureClassification.transientFailure(450, "TRANSIENT_SMTP_4XX", "Temporary SMTP failure indicated by enhanced status code: " + enhMatcher.group());
                    }
                }

                // Check 3-digit SMTP code (exclude port references like :587 or port 587)
                Matcher codeMatcher = SMTP_CODE_PATTERN.matcher(msg);
                while (codeMatcher.find()) {
                    int code;
                    try {
                        code = Integer.parseInt(codeMatcher.group(1));
                    } catch (NumberFormatException ignored) {
                        continue;
                    }

                    // Check if it looks like a port reference
                    int start = codeMatcher.start();
                    String prefix = msg.substring(Math.max(0, start - 6), start).toLowerCase();
                    if (prefix.contains("port") || prefix.endsWith(":")) {
                        continue; // skip port numbers
                    }

                    if (code == 535) {
                        return SmtpFailureClassification.permanent(535, "AUTHENTICATION_FAILURE", "SMTP authentication failure (535)");
                    }
                    if (KNOWN_PERMANENT_5XX_CODES.contains(code)) {
                        return SmtpFailureClassification.permanent(code, "PERMANENT_SMTP_5XX", "Permanent SMTP rejection: " + code);
                    }
                    if (KNOWN_TRANSIENT_4XX_CODES.contains(code)) {
                        return SmtpFailureClassification.transientFailure(code, "TRANSIENT_SMTP_4XX", "Temporary SMTP failure: " + code);
                    }
                }
            }
        }

        // Pass 6: Network, Socket, and Connection Timeout Conditions (Transient)
        for (Throwable t : chain) {
            if (isNetworkOrTimeoutFailure(t)) {
                return SmtpFailureClassification.transientFailure(
                        null,
                        "NETWORK_CONNECTIVITY",
                        "Network/connectivity issue: " + t.getClass().getSimpleName() + " - " + t.getMessage()
                );
            }
        }

        // Pass 7: Conservative fallback for unclassified transport failures
        // Treat as transient so temporary infrastructure issues get retried up to maxAttempts
        return SmtpFailureClassification.transientFailure(
                null,
                "UNCLASSIFIED_TRANSPORT",
                "Unclassified transport failure: " + root.getClass().getSimpleName()
        );
    }

    public static List<Throwable> collectExceptionChain(Throwable root) {
        List<Throwable> result = new ArrayList<>();
        if (root == null) {
            return result;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Queue<Throwable> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            Throwable current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            result.add(current);

            // 1. Spring MailSendException
            if (current instanceof MailSendException mse) {
                if (mse.getMessageExceptions() != null) {
                    for (Exception me : mse.getMessageExceptions()) {
                        if (me != null) queue.add(me);
                    }
                }
                if (mse.getFailedMessages() != null) {
                    for (Exception fe : mse.getFailedMessages().values()) {
                        if (fe != null) queue.add(fe);
                    }
                }
            }

            // 2. Jakarta MessagingException nextException
            if (current instanceof MessagingException me) {
                Exception next = me.getNextException();
                if (next != null) {
                    queue.add(next);
                }
            }

            // 3. Cause
            if (current.getCause() != null) {
                queue.add(current.getCause());
            }

            // 4. Suppressed
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null) {
                    queue.add(suppressed);
                }
            }
        }

        return result;
    }

    private static boolean isAuthenticationFailure(Throwable t) {
        if (t instanceof MailAuthenticationException) {
            return true;
        }
        if (t instanceof jakarta.mail.AuthenticationFailedException) {
            return true;
        }
        String className = t.getClass().getName();
        return className.contains("AuthenticationFailedException");
    }

    private static Optional<Integer> extractStructuredReturnCode(Throwable t) {
        if (t == null) {
            return Optional.empty();
        }

        if (t instanceof SMTPSendFailedException e) {
            return Optional.of(e.getReturnCode());
        }
        if (t instanceof SMTPAddressFailedException e) {
            return Optional.of(e.getReturnCode());
        }
        if (t instanceof SMTPSenderFailedException e) {
            return Optional.of(e.getReturnCode());
        }

        // Reflection fallback
        try {
            Method m = t.getClass().getMethod("getReturnCode");
            if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                return Optional.of((Integer) m.invoke(t));
            }
        } catch (Exception ignored) {
        }

        return Optional.empty();
    }

    private static boolean isNetworkOrTimeoutFailure(Throwable t) {
        if (t instanceof SocketTimeoutException
                || t instanceof ConnectException
                || t instanceof SocketException
                || t instanceof UnknownHostException
                || t instanceof InterruptedIOException) {
            return true;
        }

        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            return lower.contains("timed out")
                    || lower.contains("timeout")
                    || lower.contains("connection refused")
                    || lower.contains("connection reset")
                    || lower.contains("network is unreachable")
                    || lower.contains("host is unreachable")
                    || lower.contains("could not connect to smtp host")
                    || lower.contains("broken pipe");
        }

        return false;
    }

    public static String sanitizeErrorMessage(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown error";
        }
        String sanitized = error.replaceAll("(?i)password=[^&;\\s]+", "password=***")
                .replaceAll("(?i)bearer\\s+[a-zA-Z0-9._-]+", "bearer ***")
                .replaceAll("(?i)secret=[^&;\\s]+", "secret=***");
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500);
        }
        return sanitized;
    }
}
